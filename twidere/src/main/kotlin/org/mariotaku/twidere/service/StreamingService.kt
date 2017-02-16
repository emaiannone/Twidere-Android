package org.mariotaku.twidere.service

import android.accounts.AccountManager
import android.accounts.OnAccountsUpdateListener
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.support.v4.app.NotificationCompat
import android.support.v4.util.SimpleArrayMap
import android.util.Log
import org.mariotaku.ktextension.addOnAccountsUpdatedListenerSafe
import org.mariotaku.ktextension.removeOnAccountsUpdatedListenerSafe
import org.mariotaku.microblog.library.MicroBlogException
import org.mariotaku.microblog.library.twitter.TwitterUserStream
import org.mariotaku.microblog.library.twitter.UserStreamCallback
import org.mariotaku.microblog.library.twitter.model.*
import org.mariotaku.sqliteqb.library.Expression
import org.mariotaku.twidere.BuildConfig
import org.mariotaku.twidere.R
import org.mariotaku.twidere.TwidereConstants.LOGTAG
import org.mariotaku.twidere.activity.SettingsActivity
import org.mariotaku.twidere.extension.model.newMicroBlogInstance
import org.mariotaku.twidere.model.AccountDetails
import org.mariotaku.twidere.model.AccountPreferences
import org.mariotaku.twidere.model.UserKey
import org.mariotaku.twidere.model.account.cred.OAuthCredentials
import org.mariotaku.twidere.model.util.AccountUtils
import org.mariotaku.twidere.provider.TwidereDataStore.*
import org.mariotaku.twidere.util.DataStoreUtils
import org.mariotaku.twidere.util.DebugLog
import org.mariotaku.twidere.util.TwidereArrayUtils
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.Charset

class StreamingService : Service() {

    private val callbacks = SimpleArrayMap<UserKey, UserStreamCallback>()

    private var notificationManager: NotificationManager? = null

    private var accountKeys: Array<UserKey>? = null

    private val accountChangeObserver = OnAccountsUpdateListener {
        if (!TwidereArrayUtils.contentMatch(accountKeys, DataStoreUtils.getActivatedAccountKeys(this@StreamingService))) {
            initStreaming()
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        DebugLog.d(LOGTAG, "Stream service started.")
        initStreaming()
        AccountManager.get(this).addOnAccountsUpdatedListenerSafe(accountChangeObserver, updateImmediately = false)
    }

    override fun onDestroy() {
        clearTwitterInstances()
        AccountManager.get(this).removeOnAccountsUpdatedListenerSafe(accountChangeObserver)
        DebugLog.d(LOGTAG, "Stream service stopped.")
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    private fun clearTwitterInstances() {
        var i = 0
        val j = callbacks.size()
        while (i < j) {
            Thread(ShutdownStreamTwitterRunnable(callbacks.valueAt(i))).start()
            i++
        }
        callbacks.clear()
        notificationManager!!.cancel(NOTIFICATION_SERVICE_STARTED)
    }

    private fun initStreaming() {
        if (!BuildConfig.DEBUG) return
        setTwitterInstances()
        updateStreamState()
    }

    private fun setTwitterInstances(): Boolean {
        val accountsList = AccountUtils.getAllAccountDetails(AccountManager.get(this), true).filter { it.credentials is OAuthCredentials }
        val accountKeys = accountsList.map { it.key }.toTypedArray()
        val activatedPreferences = AccountPreferences.getAccountPreferences(this, accountKeys)
        DebugLog.d(LOGTAG, "Setting up twitter stream instances")
        this.accountKeys = accountKeys
        clearTwitterInstances()
        var result = false
        accountsList.forEachIndexed { i, account ->
            val preferences = activatedPreferences[i]
            if (!preferences.isStreamingEnabled) {
                return@forEachIndexed
            }
            val twitter = account.newMicroBlogInstance(context = this, cls = TwitterUserStream::class.java)
            val callback = TwidereUserStreamCallback(this, account)
            callbacks.put(account.key, callback)
            object : Thread() {
                override fun run() {
                    twitter.getUserStream(callback)
                    Log.d(LOGTAG, String.format("Stream %s disconnected", account.key))
                    callbacks.remove(account.key)
                    updateStreamState()
                }
            }.start()
            result = result or true
        }
        return result
    }

    private fun updateStreamState() {
        if (callbacks.size() > 0) {
            val intent = Intent(this, SettingsActivity::class.java)
            val contentIntent = PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT)
            val contentTitle = getString(R.string.app_name)
            val contentText = getString(R.string.timeline_streaming_running)
            val builder = NotificationCompat.Builder(this)
            builder.setOngoing(true)
            builder.setSmallIcon(R.drawable.ic_stat_refresh)
            builder.setContentTitle(contentTitle)
            builder.setContentText(contentText)
            builder.setContentIntent(contentIntent)
            notificationManager!!.notify(NOTIFICATION_SERVICE_STARTED, builder.build())
        } else {
            notificationManager!!.cancel(NOTIFICATION_SERVICE_STARTED)
        }
    }

    internal class ShutdownStreamTwitterRunnable(private val callback: UserStreamCallback?) : Runnable {

        override fun run() {
            if (callback == null) return
            Log.d(LOGTAG, "Disconnecting stream")
            callback.disconnect()
        }

    }

    internal class TwidereUserStreamCallback(
            private val context: Context,
            private val account: AccountDetails
    ) : UserStreamCallback() {

        private var statusStreamStarted: Boolean = false
        private val mentionsStreamStarted: Boolean = false

        override fun onConnected() {

        }

        override fun onBlock(source: User, blockedUser: User) {
            val message = String.format("%s blocked %s", source.screenName, blockedUser.screenName)
            Log.d(LOGTAG, message)
        }

        override fun onDirectMessageDeleted(event: DeletionEvent) {
            val where = Expression.equalsArgs(Messages.MESSAGE_ID).sql
            val whereArgs = arrayOf(event.id)
            context.contentResolver.delete(Messages.CONTENT_URI, where, whereArgs)
        }

        override fun onStatusDeleted(event: DeletionEvent) {
            val statusId = event.id
            context.contentResolver.delete(Statuses.CONTENT_URI, Expression.equalsArgs(Statuses.STATUS_ID).sql,
                    arrayOf(statusId))
            context.contentResolver.delete(Activities.AboutMe.CONTENT_URI, Expression.equalsArgs(Activities.STATUS_ID).sql,
                    arrayOf(statusId))
        }

        @Throws(IOException::class)
        override fun onDirectMessage(directMessage: DirectMessage) {
            if (directMessage.id == null) return

        }

        override fun onException(ex: Throwable) {
            if (ex is MicroBlogException) {
                Log.w(LOGTAG, String.format("Error %d", ex.statusCode), ex)
                val response = ex.httpResponse
                if (response != null) {
                    try {
                        val body = response.body
                        if (body != null) {
                            val os = ByteArrayOutputStream()
                            body.writeTo(os)
                            val charsetName: String
                            val contentType = body.contentType()
                            if (contentType != null) {
                                val charset = contentType.charset
                                if (charset != null) {
                                    charsetName = charset.name()
                                } else {
                                    charsetName = Charset.defaultCharset().name()
                                }
                            } else {
                                charsetName = Charset.defaultCharset().name()
                            }
                            Log.w(LOGTAG, os.toString(charsetName))
                        }
                    } catch (e: IOException) {
                        Log.w(LOGTAG, e)
                    }

                }
            } else {
                Log.w(LOGTAG, ex)
            }
        }

        override fun onFavorite(source: User, target: User, targetStatus: Status) {
            val message = String.format("%s favorited %s's tweet: %s", source.screenName,
                    target.screenName, targetStatus.extendedText)
            Log.d(LOGTAG, message)
        }

        override fun onFollow(source: User, followedUser: User) {
            val message = String
                    .format("%s followed %s", source.screenName, followedUser.screenName)
            Log.d(LOGTAG, message)
        }

        override fun onFriendList(friendIds: LongArray) {

        }

        override fun onScrubGeo(userId: Long, upToStatusId: Long) {
            val resolver = context.contentResolver

            val where = Expression.and(Expression.equalsArgs(Statuses.USER_KEY),
                    Expression.greaterEqualsArgs(Statuses.SORT_ID)).sql
            val whereArgs = arrayOf(userId.toString(), upToStatusId.toString())
            val values = ContentValues()
            values.putNull(Statuses.LOCATION)
            resolver.update(Statuses.CONTENT_URI, values, where, whereArgs)
        }

        override fun onStallWarning(warn: Warning) {

        }

        @Throws(IOException::class)
        override fun onStatus(status: Status) {

        }

        override fun onTrackLimitationNotice(numberOfLimitedStatuses: Int) {

        }

        override fun onUnblock(source: User, unblockedUser: User) {
            val message = String.format("%s unblocked %s", source.screenName,
                    unblockedUser.screenName)
            Log.d(LOGTAG, message)
        }

        override fun onUnfavorite(source: User, target: User, targetStatus: Status) {
            val message = String.format("%s unfavorited %s's tweet: %s", source.screenName,
                    target.screenName, targetStatus.extendedText)
            Log.d(LOGTAG, message)
        }

        override fun onUserListCreation(listOwner: User, list: UserList) {

        }

        override fun onUserListDeletion(listOwner: User, list: UserList) {

        }

        override fun onUserListMemberAddition(addedMember: User, listOwner: User, list: UserList) {

        }

        override fun onUserListMemberDeletion(deletedMember: User, listOwner: User, list: UserList) {

        }

        override fun onUserListSubscription(subscriber: User, listOwner: User, list: UserList) {

        }

        override fun onUserListUnsubscription(subscriber: User, listOwner: User, list: UserList) {

        }

        override fun onUserListUpdate(listOwner: User, list: UserList) {

        }

        override fun onUserProfileUpdate(updatedUser: User) {

        }
    }

    companion object {

        private val NOTIFICATION_SERVICE_STARTED = 1

    }

}
