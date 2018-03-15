package scorex.api.http.assets

import io.swagger.annotations.ApiModelProperty
import play.api.libs.json.Json
import scorex.transaction.assets.MassTransferTransaction.Transfer

case class MassTransferRequest(@ApiModelProperty(value = "Version")
                               version: Option[Byte],
                               assetId: Option[String],
                               sender: String,
                               transfers: List[Transfer],
                               fee: Long,
                               attachment: Option[String],
                               timestamp: Option[Long] = None)

object MassTransferRequest {
  implicit val reads = Json.reads[MassTransferRequest]
}
