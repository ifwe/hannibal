package models

import play.api.libs.json.{Json, Writes}

object JsonConversions {

  implicit val WritesTable = Json.writes[Table]
  implicit val MetricRecordWrites = Json.writes[MetricRecord]
  implicit val MetricWrites = Json.writes[Metric]

  implicit val WritesRegion = new Writes[Region] {
    def writes(r: Region) = Json.obj(
      "regionName" -> r.regionName,
      "serverName" -> r.serverName,
      "serverHostName" -> r.serverHostName,
      "serverPort" -> r.serverPort,
      "serverInfoPort" -> r.serverInfoPort,
      "storefiles" -> r.storefiles,
      "stores" -> r.stores,
      "storefileSizeMB" -> r.storefileSizeMB,
      "memstoreSizeMB" -> r.memstoreSizeMB,
      "tableName" -> r.tableName,
      "startKey" -> r.startKey,
      "regionIdTimestamp" -> r.regionIdTimestamp,
      "regionURI" -> r.regionURI,
      "serverInfoUrl" -> r.serverInfoUrl
    )
  }

}
