package scorex.transaction

import com.wavesplatform.TransactionGen
import org.scalatest._
import org.scalatest.prop.PropertyChecks
import scorex.transaction.assets.ReissueTransaction

import scala.util.Try

class ReissueTransactionSpecification extends PropSpec with PropertyChecks with Matchers with TransactionGen {

  def parseBytes(bytes: Array[Byte]): Try[ReissueTransaction] = Try {
    require(bytes.head == ReissueTransaction.typeId)
    ReissueTransaction.parseTail(bytes.tail).get
  }

  property("Reissue serialization roundtrip") {
    forAll(reissueGen) { issue: ReissueTransaction =>
      val recovered = parseBytes(issue.bytes()).get
      recovered.bytes() shouldEqual issue.bytes()
    }
  }

  property("Reissue serialization from TypedTransaction") {
    forAll(reissueGen) { issue: ReissueTransaction =>
      val recovered = TransactionParser.parseBytes(issue.bytes()).get
      recovered.bytes() shouldEqual issue.bytes()
    }
  }

}
