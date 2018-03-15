package scorex.api.http.assets

import io.swagger.annotations.ApiModelProperty
import play.api.libs.json._

case class BurnRequest(@ApiModelProperty(value = "Version")
                       version: Option[Byte],
                       sender: String,
                       assetId: String,
                       quantity: Long,
                       fee: Long,
                       timestamp: Option[Long] = None)

object BurnRequest {
  implicit val burnFormat: Format[BurnRequest] = Json.format
}
