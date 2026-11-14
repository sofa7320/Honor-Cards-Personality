package com.cardpersonality.honor.data

import android.net.Uri

/**
 * Wire contract shared by:
 *  - the app (UI + FaceProvider)
 *  - the hook running inside com.hihonor.wallet (via ContentResolver)
 *
 * Image URI:  content://com.cardpersonality.honor.face/card/<type>/<id>?v=<configVersion>
 *             -> JPEG bytes, 672x416 (aspect 336:208 = 1.6153846), rectangular (no rounded corners).
 *
 * ContentResolver.call() methods (authority = [AUTHORITY]):
 *  - ping              : Bundle{pong, version}
 *  - getFaceConfig     : extras type,id -> Bundle{json (config object or "{}"), version, hasCustomFace}
 *  - getAllConfigs     : -> Bundle{json ({"version":N,"cards":{"type/id":{...}}}), version}
 *  - reportCards       : extras json = JSON array of CardInfo -> Bundle{ok, version}
 *  - getCardReport     : -> Bundle{json ({"version":N,"updatedAt":n,"cards":[...]})}
 */
object Contract {
    const val AUTHORITY = "com.cardpersonality.honor.face"
    const val BASE_URI = "content://$AUTHORITY"

    const val PATH_CARD = "card"

    const val CALL_PING = "ping"
    const val CALL_GET_CONFIG = "getFaceConfig"
    const val CALL_GET_ALL_CONFIGS = "getAllConfigs"
    const val CALL_REPORT_CARDS = "reportCards"
    const val CALL_GET_CARD_REPORT = "getCardReport"

    const val EXTRA_TYPE = "type"
    const val EXTRA_ID = "id"
    const val EXTRA_JSON = "json"
    const val EXTRA_VERSION = "version"
    const val EXTRA_HAS_CUSTOM_FACE = "hasCustomFace"
    const val EXTRA_OK = "ok"
    const val EXTRA_ERROR = "error"

    /** Card face canvas used by the provider/exports (3x of 336x208 dp). */
    const val FACE_WIDTH = 1008
    const val FACE_HEIGHT = 624

    /** 336:208 */
    const val FACE_ASPECT = 1.6153846f

    const val JPEG_QUALITY = 92

    fun baseUri(): Uri = Uri.parse(BASE_URI)

    fun cardUri(type: String, id: String, version: Int): Uri =
        Uri.parse("$BASE_URI/$PATH_CARD/$type/${Uri.encode(id)}?v=$version")

    fun cardUri(type: String, id: String, config: CardFaceConfig): Uri =
        cardUri(type, id, config.version)
}
