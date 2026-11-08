package com.unchunks.echomark.data.local.encryption

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 保存したデータ(DB)の暗号化の状態。設定画面に出す */
enum class DatabaseEncryptionState {
    /** まだ DB を開いていない */
    UNKNOWN,
    ENCRYPTED,

    /** 暗号化に失敗したので、今回は平文のまま開いている(次の起動でやり直す) */
    PLAINTEXT_FALLBACK,

    /** 鍵が失われ、暗号化した DB を読めなかったので作り直した(利用者が閉じるまで知らせる) */
    KEY_LOST
}

/** DB の暗号化の状態を持つ。鍵が失われたことは、利用者が知らせを閉じるまで覚えておく(バックアップされない場所のファイル) */
@Singleton
class DatabaseEncryptionStatus @Inject constructor(
    @ApplicationContext context: Context
) {
    private val keyLostFlag = File(context.noBackupFilesDir, KEY_LOST_FLAG)
    private val _state = MutableStateFlow(if (keyLostFlag.isFile) DatabaseEncryptionState.KEY_LOST else DatabaseEncryptionState.UNKNOWN)
    val state: StateFlow<DatabaseEncryptionState> = _state.asStateFlow()

    fun record(outcome: EncryptionOutcome) {
        _state.value = when (outcome) {
            is EncryptionOutcome.Encrypted -> if (keyLostFlag.isFile) DatabaseEncryptionState.KEY_LOST else DatabaseEncryptionState.ENCRYPTED
            is EncryptionOutcome.PlaintextFallback -> DatabaseEncryptionState.PLAINTEXT_FALLBACK
            is EncryptionOutcome.KeyLost -> {
                keyLostFlag.parentFile?.mkdirs()
                keyLostFlag.writeText("1")
                DatabaseEncryptionState.KEY_LOST
            }
        }
    }

    /** 鍵が失われた知らせを閉じた */
    fun dismissKeyLost() {
        keyLostFlag.delete()
        if (_state.value == DatabaseEncryptionState.KEY_LOST) _state.value = DatabaseEncryptionState.ENCRYPTED
    }

    private companion object {
        const val KEY_LOST_FLAG = "db_key_lost"
    }
}

/**
 * Room の DB を最初に開くときに、[prepare](暗号化の準備・平文の DB の暗号化)を行い、その結果で開き方を決める。
 * Room は DB を裏のスレッドで開くので、暗号化も裏のスレッドで行われる(画面を止めない)。
 * - 暗号化できた・新しく作る・鍵が失われて作り直す: SQLCipher で開く
 * - 暗号化に失敗した: 平文のまま開く(次の起動でやり直す)
 */
class EncryptingOpenHelperFactory(
    private val prepare: () -> EncryptionOutcome,
    private val onOutcome: (EncryptionOutcome) -> Unit
) : SupportSQLiteOpenHelper.Factory {

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper = LazyHelper(configuration)

    private inner class LazyHelper(private val configuration: SupportSQLiteOpenHelper.Configuration) : SupportSQLiteOpenHelper {
        private var delegate: SupportSQLiteOpenHelper? = null
        private var writeAheadLogging: Boolean? = null

        override val databaseName: String? get() = configuration.name

        override fun setWriteAheadLoggingEnabled(enabled: Boolean) = synchronized(this) {
            writeAheadLogging = enabled
            delegate?.setWriteAheadLoggingEnabled(enabled)
            Unit
        }

        override val writableDatabase: SupportSQLiteDatabase get() = delegate().writableDatabase

        override val readableDatabase: SupportSQLiteDatabase get() = delegate().readableDatabase

        override fun close() = synchronized(this) {
            delegate?.close()
            Unit
        }

        private fun delegate(): SupportSQLiteOpenHelper = synchronized(this) {
            delegate ?: run {
                val outcome = prepare()
                onOutcome(outcome)
                val factory = when (outcome) {
                    // SQLCipher のヘルパーがパスフレーズの配列を消すことがあるため、写しを渡す
                    is EncryptionOutcome.Encrypted -> SupportOpenHelperFactory(outcome.passphrase.copyOf())
                    is EncryptionOutcome.KeyLost -> SupportOpenHelperFactory(outcome.passphrase.copyOf())
                    is EncryptionOutcome.PlaintextFallback -> FrameworkSQLiteOpenHelperFactory()
                }
                factory.create(configuration).also { helper ->
                    writeAheadLogging?.let(helper::setWriteAheadLoggingEnabled)
                    delegate = helper
                }
            }
        }
    }
}
