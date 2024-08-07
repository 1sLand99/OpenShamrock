package qq.service.contact

import com.tencent.common.app.AppInterface
import com.tencent.mobileqq.data.Card
import com.tencent.mobileqq.profilecard.api.IProfileDataService
import com.tencent.mobileqq.profilecard.api.IProfileProtocolConst.PARAM_SELF_UIN
import com.tencent.mobileqq.profilecard.api.IProfileProtocolConst.PARAM_TARGET_UIN
import com.tencent.mobileqq.profilecard.api.IProfileProtocolService
import com.tencent.mobileqq.profilecard.observer.ProfileCardObserver
import com.tencent.protofile.join_group_link.join_group_link
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.fuqiuluo.shamrock.tools.decodeToOidb
import moe.fuqiuluo.shamrock.tools.slice
import qq.service.internals.NTServiceFetcher
import qq.service.QQInterfaces
import tencent.im.oidb.cmd0x11b2.oidb_0x11b2
import tencent.im.oidb.oidb_sso
import kotlin.coroutines.resume

internal object ContactHelper: QQInterfaces() {
    const val FROM_C2C_AIO = 2
    const val FROM_CONDITION_SEARCH = 9
    const val FROM_CONTACTS_TAB = 5
    const val FROM_FACE_2_FACE_ADD_FRIEND = 11
    const val FROM_MAYKNOW_FRIEND = 3
    const val FROM_QCIRCLE = 4
    const val FROM_QQ_TROOP = 1
    const val FROM_QZONE = 7
    const val FROM_SCAN = 6
    const val FROM_SEARCH = 8
    const val FROM_SETTING_ME = 12
    const val FROM_SHARE_CARD = 10

    const val PROFILE_CARD_IS_BLACK = 2
    const val PROFILE_CARD_IS_BLACKED = 1
    const val PROFILE_CARD_NOT_BLACK = 3

    const val SUB_FROM_C2C_AIO = 21
    const val SUB_FROM_C2C_INTERACTIVE_LOGO = 25
    const val SUB_FROM_C2C_LEFT_SLIDE = 23
    const val SUB_FROM_C2C_OTHER = 24
    const val SUB_FROM_C2C_SETTING = 22
    const val SUB_FROM_C2C_TOFU = 26
    const val SUB_FROM_CONDITION_SEARCH_OTHER = 99
    const val SUB_FROM_CONDITION_SEARCH_RESULT = 91
    const val SUB_FROM_CONTACTS_FRIEND_TAB = 51
    const val SUB_FROM_CONTACTS_TAB = 55
    const val SUB_FROM_FACE_2_FACE_ADD_FRIEND_RESULT_AVATAR = 111
    const val SUB_FROM_FACE_2_FACE_OTHER = 119
    const val SUB_FROM_FRIEND_APPLY = 56
    const val SUB_FROM_FRIEND_NOTIFY_MORE = 57
    const val SUB_FROM_FRIEND_NOTIFY_TAB = 54
    const val SUB_FROM_GROUPING_TAB = 52
    const val SUB_FROM_MAYKNOW_FRIEND_CONTACT_TAB = 31
    const val SUB_FROM_MAYKNOW_FRIEND_CONTACT_TAB_MORE = 37
    const val SUB_FROM_MAYKNOW_FRIEND_FIND_PEOPLE = 34
    const val SUB_FROM_MAYKNOW_FRIEND_FIND_PEOPLE_MORE = 39
    const val SUB_FROM_MAYKNOW_FRIEND_FIND_PEOPLE_SEARCH = 36
    const val SUB_FROM_MAYKNOW_FRIEND_NEW_FRIEND_PAGE = 32
    const val SUB_FROM_MAYKNOW_FRIEND_OTHER = 35
    const val SUB_FROM_MAYKNOW_FRIEND_SEARCH = 33
    const val SUB_FROM_MAYKNOW_FRIEND_SEARCH_MORE = 38
    const val SUB_FROM_PHONE_LIST_TAB = 53
    const val SUB_FROM_QCIRCLE_OTHER = 42
    const val SUB_FROM_QCIRCLE_PROFILE = 41
    const val SUB_FROM_QQ_TROOP_ACTIVE_MEMBER = 15
    const val SUB_FROM_QQ_TROOP_ADMIN = 16
    const val SUB_FROM_QQ_TROOP_AIO = 11
    const val SUB_FROM_QQ_TROOP_MEMBER = 12
    const val SUB_FROM_QQ_TROOP_OTHER = 14
    const val SUB_FROM_QQ_TROOP_SETTING_MEMBER_LIST = 17
    const val SUB_FROM_QQ_TROOP_TEMP_SESSION = 13
    const val SUB_FROM_QRCODE_SCAN_DRAWER = 64
    const val SUB_FROM_QRCODE_SCAN_NEW = 61
    const val SUB_FROM_QRCODE_SCAN_OLD = 62
    const val SUB_FROM_QRCODE_SCAN_OTHER = 69
    const val SUB_FROM_QRCODE_SCAN_PROFILE = 63
    const val SUB_FROM_QZONE_HOME = 71
    const val SUB_FROM_QZONE_OTHER = 79
    const val SUB_FROM_SEARCH_CONTACT_TAB_MORE_FIND_PROFILE = 83
    const val SUB_FROM_SEARCH_FIND_PROFILE_TAB = 82
    const val SUB_FROM_SEARCH_MESSAGE_TAB_MORE_FIND_PROFILE = 84
    const val SUB_FROM_SEARCH_NEW_FRIEND_MORE_FIND_PROFILE = 85
    const val SUB_FROM_SEARCH_OTHER = 89
    const val SUB_FROM_SEARCH_TAB = 81
    const val SUB_FROM_SETTING_ME_AVATAR = 121
    const val SUB_FROM_SETTING_ME_OTHER = 129
    const val SUB_FROM_SHARE_CARD_C2C = 101
    const val SUB_FROM_SHARE_CARD_OTHER = 109
    const val SUB_FROM_SHARE_CARD_TROOP = 102
    const val SUB_FROM_TYPE_DEFAULT = 0

    private val refreshCardLock by lazy { Mutex() }

    suspend fun voteUser(target: Long, count: Int): Result<Unit> {
        if(count !in 1 .. 20) {
            return Result.failure(IllegalArgumentException("vote count must be in 1 .. 20"))
        }
        val card = getProfileCard(target).onFailure {
            return Result.failure(RuntimeException("unable to fetch contact info"))
        }.getOrThrow()
        sendExtra("VisitorSvc.ReqFavorite") {
            it.putLong(PARAM_SELF_UIN, app.longAccountUin)
            it.putLong(PARAM_TARGET_UIN, target)
            it.putByteArray("vCookies", card.vCookies)
            it.putBoolean("nearby_people", true)
            it.putInt("favoriteSource", FROM_CONTACTS_TAB)
            it.putInt("iCount", count)
            it.putInt("from", FROM_CONTACTS_TAB)
        }
        return Result.success(Unit)
    }

    suspend fun getProfileCard(uin: Long): Result<Card> {
        return getProfileCardFromCache(uin).onFailure {
            return refreshAndGetProfileCard(uin)
        }
    }

    fun getProfileCardFromCache(uin: Long): Result<Card> {
        val profileDataService = app
            .getRuntimeService(IProfileDataService::class.java, "all")
        val card = profileDataService.getProfileCard(uin.toString(), true)
        return if (card == null || card.strNick.isNullOrEmpty()) {
            Result.failure(Exception("unable to fetch profile card"))
        } else {
            Result.success(card)
        }
    }

    suspend fun refreshAndGetProfileCard(uin: Long): Result<Card> {
        require(app is AppInterface)
        val dataService = app
            .getRuntimeService(IProfileDataService::class.java, "all")
        val card = refreshCardLock.withLock {
            suspendCancellableCoroutine {
                app.addObserver(object: ProfileCardObserver() {
                    override fun onGetProfileCard(success: Boolean, obj: Any) {
                        app.removeObserver(this)
                        if (!success || obj !is Card) {
                            it.resume(null)
                        } else {
                            dataService.saveProfileCard(obj)
                            it.resume(obj)
                        }
                    }
                })
                app.getRuntimeService(IProfileProtocolService::class.java, "all")
                    .requestProfileCard(app.currentUin, uin.toString(), 12, 0L, 0.toByte(), 0L, 0L, null, "", 0L, 10004, null, 0.toByte())
            }
        }
        return if (card == null || card.strNick.isNullOrEmpty()) {
            Result.failure(Exception("unable to fetch profile card"))
        } else {
            Result.success(card)
        }
    }

    suspend fun getUinByUidAsync(uid: String): String {
        if (uid.isBlank() || uid == "0") {
            return "0"
        }

        val kernelService = NTServiceFetcher.kernelService
        val sessionService = kernelService.wrapperSession

        return suspendCancellableCoroutine { continuation ->
            sessionService.uixConvertService.getUin(hashSetOf(uid)) {
                continuation.resume(it)
            }
        }[uid]?.toString() ?: "0"
    }

    suspend fun getUidByUinAsync(peerId: Long): String {
        val kernelService = NTServiceFetcher.kernelService
        val sessionService = kernelService.wrapperSession
        return suspendCancellableCoroutine { continuation ->
            sessionService.uixConvertService.getUid(hashSetOf(peerId)) {
                continuation.resume(it)
            }
        }[peerId]!!
    }

    suspend fun getSharePrivateArkMsg(peerId: Long): String {
        val reqBody = oidb_0x11b2.BusinessCardV3Req()
        reqBody.uin.set(peerId)
        reqBody.jump_url.set("mqqapi://card/show_pslcard?src_type=internal&source=sharecard&version=1&uin=$peerId")

        val fromServiceMsg = sendOidbAW("OidbSvcTrpcTcp.0x11ca_0", 4790, 0, reqBody.toByteArray())
            ?: error("unable to fetch contact ark_json_text")

        val body = fromServiceMsg.decodeToOidb()
        val rsp = oidb_0x11b2.BusinessCardV3Rsp()
        rsp.mergeFrom(body.bytes_bodybuffer.get().toByteArray())
        return rsp.signed_ark_msg.get()
    }

    suspend fun getShareTroopArkMsg(groupId: Long): String {
        val reqBody = join_group_link.ReqBody()
        reqBody.get_ark.set(true)
        reqBody.type.set(1)
        reqBody.group_code.set(groupId)
        val fromServiceMsg = sendBufferAW("GroupSvc.JoinGroupLink", true, reqBody.toByteArray())
            ?: error("unable to fetch contact ark_json_text")
        val body = join_group_link.RspBody()
        body.mergeFrom(fromServiceMsg.wupBuffer.slice(4))
        return body.signed_ark.get().toStringUtf8()
    }
}