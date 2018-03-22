package com.wavesplatform.state2.diffs

import cats._
import com.wavesplatform.{NoShrink, TransactionGen, WithDB}
import com.wavesplatform.lang.{Parser, TypeChecker}
import fastparse.core.Parsed
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.prop.PropertyChecks
import org.scalatest.{Matchers, PropSpec}
import scorex.account.AddressScheme
import scorex.lagonaki.mocks.TestBlock
import scorex.transaction.GenesisTransaction
import scorex.transaction.assets._
import scorex.transaction.smart.Script
import com.wavesplatform.utils.dummyTypeCheckerContext
import com.wavesplatform.state2._
import com.wavesplatform.state2.diffs.smart.smartEnabledFS

class AssetTransactionsDiffTest extends PropSpec with PropertyChecks with Matchers with TransactionGen with NoShrink with WithDB {

  def issueReissueBurnTxs(isReissuable: Boolean): Gen[((GenesisTransaction, IssueTransaction), (ReissueTransaction, BurnTransaction))] =
    for {
      master <- accountGen
      ts     <- timestampGen
      genesis: GenesisTransaction = GenesisTransaction.create(master, ENOUGH_AMT, ts).right.get
      ia                     <- positiveLongGen
      ra                     <- positiveLongGen
      ba                     <- positiveLongGen.suchThat(x => x < ia + ra)
      (issue, reissue, burn) <- issueReissueBurnGeneratorP(ia, ra, ba, master) suchThat (_._1.reissuable == isReissuable)
    } yield ((genesis, issue), (reissue, burn))

  property("Issue+Reissue+Burn do not break waves invariant and updates state") {
    forAll(issueReissueBurnTxs(isReissuable = true)) {
      case (((gen, issue), (reissue, burn))) =>
        assertDiffAndState(Seq(TestBlock.create(Seq(gen, issue))), TestBlock.create(Seq(reissue, burn))) {
          case (blockDiff, newState) =>
            val totalPortfolioDiff = Monoid.combineAll(blockDiff.portfolios.values)

            totalPortfolioDiff.balance shouldBe 0
            totalPortfolioDiff.effectiveBalance shouldBe 0
            totalPortfolioDiff.assets shouldBe Map(reissue.assetId -> (reissue.quantity - burn.amount))

            val totalAssetVolume = issue.quantity + reissue.quantity - burn.amount
            newState.portfolio(issue.sender).assets shouldBe Map(reissue.assetId -> totalAssetVolume)
        }
    }
  }

  property("Cannot reissue/burn non-existing alias") {
    val setup: Gen[(GenesisTransaction, ReissueTransaction, BurnTransaction)] = for {
      master <- accountGen
      ts     <- timestampGen
      genesis: GenesisTransaction = GenesisTransaction.create(master, ENOUGH_AMT, ts).right.get
      reissue <- reissueGen
      burn    <- burnGen
    } yield (genesis, reissue, burn)

    forAll(setup) {
      case ((gen, reissue, burn)) =>
        assertDiffEi(Seq(TestBlock.create(Seq(gen))), TestBlock.create(Seq(reissue))) { blockDiffEi =>
          blockDiffEi should produce("Referenced assetId not found")
        }
        assertDiffEi(Seq(TestBlock.create(Seq(gen))), TestBlock.create(Seq(burn))) { blockDiffEi =>
          blockDiffEi should produce("Referenced assetId not found")
        }
    }
  }

  property("Cannot reissue/burn non-owned alias") {
    val setup = for {
      ((gen, issue), (_, _)) <- issueReissueBurnTxs(isReissuable = true)
      other                  <- accountGen.suchThat(_ != issue.sender.toAddress)
      quantity               <- positiveLongGen
      reissuable2            <- Arbitrary.arbitrary[Boolean]
      fee                    <- Gen.choose(1L, 2000000L)
      timestamp              <- timestampGen
      reissue = ReissueTransaction.create(other, issue.assetId(), quantity, reissuable2, fee, timestamp).right.get
      burn    = BurnTransaction.create(other, issue.assetId(), quantity, fee, timestamp).right.get
    } yield ((gen, issue), reissue, burn)

    forAll(setup) {
      case ((gen, issue), reissue, burn) =>
        assertDiffEi(Seq(TestBlock.create(Seq(gen, issue))), TestBlock.create(Seq(reissue))) { blockDiffEi =>
          blockDiffEi should produce("Asset was issued by other address")
        }
        assertDiffEi(Seq(TestBlock.create(Seq(gen, issue))), TestBlock.create(Seq(burn))) { blockDiffEi =>
          blockDiffEi should produce("Asset was issued by other address")
        }
    }
  }

  property("Cannot reissue non-reissuable alias") {
    forAll(issueReissueBurnTxs(isReissuable = false)) {
      case ((gen, issue), (reissue, _)) =>
        assertDiffEi(Seq(TestBlock.create(Seq(gen, issue))), TestBlock.create(Seq(reissue))) { blockDiffEi =>
          blockDiffEi should produce("Asset is not reissuable")
        }
    }
  }

  private def createScript(code: String) = {
    val Parsed.Success(expr, _) = Parser(code).get
    Script(TypeChecker(dummyTypeCheckerContext, expr).explicitGet())
  }

  def genesisIssueTransferReissue(code: String): Gen[(Seq[GenesisTransaction], SmartIssueTransaction, TransferTransaction, ReissueTransaction)] =
    for {
      timestamp          <- timestampGen
      initialWavesAmount <- Gen.choose(Long.MaxValue / 1000, Long.MaxValue / 100)
      accountA           <- accountGen
      accountB           <- accountGen
      smallFee           <- Gen.choose(1l, 10l)
      genesisTx1 = GenesisTransaction.create(accountA, initialWavesAmount, timestamp).explicitGet()
      genesisTx2 = GenesisTransaction.create(accountB, initialWavesAmount, timestamp).explicitGet()
      reissuable = true
      (_, assetName, description, quantity, decimals, _, _, _) <- issueParamGen
      issue = SmartIssueTransaction
        .selfSigned(
                AddressScheme.current.chainId,
                accountA,
                assetName,
                description,
                quantity,
                decimals,
                reissuable,
                Some(createScript(code)),
                smallFee,
                timestamp + 1)
        .explicitGet()
      assetId = issue.id()
      transfer = TransferTransaction
        .create(Some(assetId), accountA, accountB, issue.quantity, timestamp + 2, None, smallFee, Array.empty)
        .explicitGet()
      reissue = ReissueTransaction.create(accountB, assetId, quantity, reissuable, smallFee, timestamp + 3).explicitGet()
    } yield (Seq(genesisTx1, genesisTx2), issue, transfer, reissue)

  property("Can issue smart asset with script") {
    forAll(for {
      acc        <- accountGen
      genesisGen <- genesisGeneratorP(acc)
      issueGen   <- smartIssueTransactionGen(acc)
    } yield (genesisGen, issueGen)) {
      case (gen, issue) =>
        assertDiffAndState(Seq(TestBlock.create(Seq(gen))), TestBlock.create(Seq(issue)), smartEnabledFS) {
          case (blockDiff, newState) =>
            newState.assetDescription(issue.id()) shouldBe Some(
              AssetDescription(issue.sender, issue.name, issue.decimals, issue.reissuable, BigInt(issue.quantity), issue.script))
            blockDiff.transactions.get(issue.id()).isDefined shouldBe true
            newState.transactionInfo(issue.id()).isDefined shouldBe true
            newState.transactionInfo(issue.id()).isDefined shouldEqual true
        }
    }
  }

  property("Can transfer when script evaluates to TRUE") {
    forAll(genesisIssueTransferReissue("true")) {
      case (gen, issue, transfer, _) =>
        assertDiffAndState(Seq(TestBlock.create(gen)), TestBlock.create(Seq(issue, transfer)), smartEnabledFS) {
          case (blockDiff, newState) =>
            val totalPortfolioDiff = Monoid.combineAll(blockDiff.portfolios.values)
            totalPortfolioDiff.assets(issue.id()) shouldEqual issue.quantity
            newState.portfolio(newState.resolveAliasEi(transfer.recipient).right.get).assets(issue.id()) shouldEqual transfer.amount
        }
    }
  }

  property("Cannot transfer when script evaluates to FALSE") {
    forAll(genesisIssueTransferReissue("false")) {
      case (gen, issue, transfer, _) =>
        assertDiffEi(Seq(TestBlock.create(gen)), TestBlock.create(Seq(issue, transfer)), smartEnabledFS)(ei =>
          ei should produce("TransactionNotAllowedByScript"))
    }
  }

  property("Cannot reissue when script evaluates to FALSE") {
    forAll(genesisIssueTransferReissue("false")) {
      case (gen, issue, _, reissue) =>
        assertDiffEi(Seq(TestBlock.create(gen)), TestBlock.create(Seq(issue, reissue)), smartEnabledFS)(ei =>
          ei should produce("TransactionNotAllowedByScript"))
    }
  }

  property("Can reissue when script evaluates to TRUE even if sender is not original issuer") {
    forAll(genesisIssueTransferReissue("true")) {
      case (gen, issue, _, reissue) =>
        assertDiffAndState(Seq(TestBlock.create(gen)), TestBlock.create(Seq(issue, reissue)), smartEnabledFS) {
          case (blockDiff, newState) =>
            val totalPortfolioDiff = Monoid.combineAll(blockDiff.portfolios.values)
            totalPortfolioDiff.assets(issue.id()) shouldEqual (issue.quantity + reissue.quantity)
        }
    }
  }

}
