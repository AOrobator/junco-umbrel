package com.sparrowwallet.sparrow.net

import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcMethod
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcOptional
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcParam
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcService
import com.google.common.collect.Iterables
import com.sparrowwallet.sparrow.EventManager
import com.sparrowwallet.sparrow.event.NewBlockEvent
import com.sparrowwallet.sparrow.event.WalletNodeHistoryChangedEvent
import org.slf4j.LoggerFactory

@JsonRpcService
class SubscriptionService {
    private val log = LoggerFactory.getLogger(SubscriptionService::class.java)

    @JsonRpcMethod("blockchain.headers.subscribe")
    fun newBlockHeaderTip(@JsonRpcParam("header") header: BlockHeaderTip) {
        ElectrumServer.updateRetrievedBlockHeaders(header.height, header.blockHeader)
        EventManager.get().post(NewBlockEvent(header.height, header.blockHeader))
    }

    @JsonRpcMethod("blockchain.scripthash.subscribe")
    fun scriptHashStatusUpdated(
        @JsonRpcParam("scripthash") scriptHash: String,
        @JsonRpcOptional @JsonRpcParam("status") status: String?
    ) {
        val existingStatuses = ElectrumServer.getSubscribedScriptHashes()[scriptHash]
        if(existingStatuses == null) {
            log.trace("Received script hash status update for non-wallet script hash: $scriptHash")
        } else if(status != null && existingStatuses.contains(status)) {
            log.debug("Received script hash status update, but status has not changed")
            return
        } else {
            val oldStatus = Iterables.getLast(existingStatuses)
            log.debug("Status updated for script hash $scriptHash, was $oldStatus now $status")
            existingStatuses.add(status)
        }

        EventManager.get().post(WalletNodeHistoryChangedEvent(scriptHash, status))
    }
}
