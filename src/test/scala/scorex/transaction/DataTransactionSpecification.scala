package scorex.transaction

import com.google.common.primitives.Shorts
import com.wavesplatform.TransactionGen
import com.wavesplatform.state2.DataEntry._
import com.wavesplatform.state2.{BinaryDataEntry, BooleanDataEntry, DataEntry, IntegerDataEntry}
import org.scalacheck.Gen
import org.scalatest._
import org.scalatest.prop.PropertyChecks
import play.api.libs.json.Json
import scorex.api.http.SignedDataRequest
import scorex.crypto.encode.Base58
import scorex.transaction.DataTransaction._

class DataTransactionSpecification extends PropSpec with PropertyChecks with Matchers with TransactionGen {

  private def checkSerialization(tx: DataTransaction): Assertion = {
    require(tx.bytes().head == DataTransaction.typeId)
    val parsed = DataTransaction.parseTail(tx.bytes().tail).get

    parsed.sender.address shouldEqual tx.sender.address
    parsed.timestamp shouldEqual tx.timestamp
    parsed.fee shouldEqual tx.fee

    parsed.data.zip(tx.data).foreach { case (r, t) =>
      r.key shouldEqual t.key
      r.value shouldEqual t.value
    }

    parsed.bytes() shouldEqual tx.bytes()
  }

  property("serialization roundtrip") {
    forAll(dataTransactionGen)(checkSerialization)
  }

  property("serialization from TypedTransaction") {
    forAll(dataTransactionGen) { tx: DataTransaction =>
      val recovered = TransactionParser.parseBytes(tx.bytes()).get
      recovered.bytes() shouldEqual tx.bytes()
    }
  }

  property("unknown type handing") {
    val badTypeIdGen = Gen.choose[Byte](3, Byte.MaxValue)
    forAll(dataTransactionGen, badTypeIdGen) { case (tx, badTypeId) =>
      val bytes = tx.bytes()
      val entryCount = Shorts.fromByteArray(bytes.drop(34))
      if (entryCount > 0) {
        val p = 36 + bytes(36) // bytes(36) is key#1 length
        val parsed = DataTransaction.parseTail((bytes.tail.take(p) :+ badTypeId) ++ bytes.drop(p + 2))
        parsed.isFailure shouldBe true
        parsed.failed.get.getMessage shouldBe s"Unknown type $badTypeId"
      }
    }
  }

  property("JSON roundtrip") {
    implicit val signedFormat = Json.format[SignedDataRequest]

    forAll(dataTransactionGen) { tx =>
      val json = tx.json()
      json.toString shouldEqual tx.toString

      val req = json.as[SignedDataRequest]
      req.senderPublicKey shouldEqual Base58.encode(tx.sender.publicKey)
      req.fee shouldEqual tx.fee
      req.timestamp shouldEqual tx.timestamp

      req.data zip tx.data foreach { case (re, te) =>
        re match {
          case BinaryDataEntry(k, v) =>
            k shouldEqual te.key
            v shouldEqual te.value
          case _: DataEntry[_] =>
            re shouldEqual te
          case _ => fail
        }
      }
    }
  }

  property("positive validation cases") {
    val keyRepeatCountGen = Gen.choose(2, MaxEntryCount)
    forAll(dataTransactionGen, keyRepeatCountGen) {
      case (DataTransaction(sender, data, fee, timestamp, proofs), keyRepeatCount) =>
        def check(data: List[DataEntry[_]]): Assertion = {
          val txEi = create(sender, data, fee, timestamp, proofs)
          txEi shouldBe Right(DataTransaction(sender, data, fee, timestamp, proofs))
          checkSerialization(txEi.right.get)
        }

        check(List.empty)  // no data
        check(List.tabulate(MaxEntryCount)(n => IntegerDataEntry(n.toString, n)))  // maximal data
        check(List.fill[DataEntry[_]](keyRepeatCount)(data.head))  // repeating keys
        check(List(BooleanDataEntry("", false)))  // empty key
        check(List(IntegerDataEntry("a" * Byte.MaxValue, 0xa)))  // max key size
        check(List(BinaryDataEntry("bin", Array.empty)))  // empty binary
        check(List(BinaryDataEntry("bin", Array.fill(MaxValueSize)(1: Byte))))  // max binary value size
    }
  }

  property("negative validation cases") {
    forAll(dataTransactionGen) {
      case DataTransaction(sender, data, fee, timestamp, proofs) =>
        val dataTooBig = List.fill(MaxEntryCount + 1)(IntegerDataEntry("key", 4))
        val dataTooBigEi = create(sender, dataTooBig, fee, timestamp, proofs)
        dataTooBigEi shouldBe Left(ValidationError.TooBigArray)

        val keyTooLong = data :+ BinaryDataEntry("a" * (MaxKeySize + 1), Array(1, 2))
        val keyTooLongEi = create(sender, keyTooLong, fee, timestamp, proofs)
        keyTooLongEi shouldBe Left(ValidationError.TooBigArray)

        val valueTooLong = data :+ BinaryDataEntry("key", Array.fill(MaxValueSize + 1)(1: Byte))
        val valueTooLongEi = create(sender, valueTooLong, fee, timestamp, proofs)
        valueTooLongEi shouldBe Left(ValidationError.TooBigArray)

        val noFeeEi = create(sender, data, 0, timestamp, proofs)
        noFeeEi shouldBe Left(ValidationError.InsufficientFee)

        val negativeFeeEi = create(sender, data, -100, timestamp, proofs)
        negativeFeeEi shouldBe Left(ValidationError.InsufficientFee)
    }
  }
}
