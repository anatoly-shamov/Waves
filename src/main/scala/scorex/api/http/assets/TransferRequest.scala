package scorex.api.http.assets

import io.swagger.annotations.ApiModelProperty
import play.api.libs.json.{Format, Json}

case class TransferRequest(@ApiModelProperty(value = "Version")
                           version: Option[Byte],
                           assetId: Option[String],
                           feeAssetId: Option[String],
                           amount: Long,
                           fee: Long,
                           sender: String,
                           attachment: Option[String],
                           recipient: String,
                           timestamp: Option[Long] = None)

object TransferRequest {
  implicit val transferFormat: Format[TransferRequest] = Json.format
}
