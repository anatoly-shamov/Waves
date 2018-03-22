package com.wavesplatform.state2.diffs.smart.scenarios

import com.wavesplatform.lang.Terms.Typed
import com.wavesplatform.state2._
import com.wavesplatform.state2.diffs.smart.smartEnabledFS
import com.wavesplatform.state2.diffs.{ENOUGH_AMT, assertDiffEi, produce}
import com.wavesplatform.{NoShrink, TransactionGen}
import org.scalatest.prop.PropertyChecks
import org.scalatest.{Matchers, PropSpec}
import scorex.lagonaki.mocks.TestBlock
import scorex.transaction.GenesisTransaction
import scorex.transaction.assets.VersionedTransferTransaction
import scorex.transaction.smart.Script

class OneProofForNonScriptedAccountTest extends PropSpec with PropertyChecks with Matchers with TransactionGen with NoShrink {

  property("exactly 1 proof required for non-scripted accounts") {

    val s = for {
      master    <- accountGen
      recepient <- accountGen
      amt       <- positiveLongGen
      fee       <- smallFeeGen
      ts        <- positiveIntGen
      genesis = GenesisTransaction.create(master, ENOUGH_AMT, ts).explicitGet()
      setScript <- selfSignedSetScriptTransactionGenP(master, Script(Typed.TRUE))
      transfer = VersionedTransferTransaction.selfSigned(None, master, recepient, amt, ts, fee, Array.emptyByteArray).explicitGet()
    } yield (genesis, setScript, transfer)

    forAll(s) {
      case ((genesis, script, transfer)) =>
        val transferWithExtraProof = transfer.copy(proofs = transfer.proofs.copy(proofs = Seq(transfer.proofs.proofs.head, ByteStr(Array(1: Byte)))))
        assertDiffEi(Seq(TestBlock.create(Seq(genesis))), TestBlock.create(Seq(transferWithExtraProof)), smartEnabledFS)(totalDiffEi =>
          totalDiffEi should produce("must have exactly 1 proof"))
    }
  }

}
