package secretspace.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import secretspace.core.AccountCrypto as ac
import java.io.File
import kotlin.test.Test

/**
 * Files made HERE, for the PC's code to open (tools/check_on_pc.py): the
 * other half of vectors.json. Written to core/build/made-on-android.json.
 */
class ExportForPcTest {
    @Test fun export() {
        val secret = ac.newSecretKey()
        val (sealed, login) = ac.resealWithNewPassword("alex", ac.newKeyset(), "pässwörd ﬁ", secret, 1, 64, 1)
        val keyset = ac.unlockAccount("alex", "pässwörd ﬁ", secret, sealed).keyset
        val id = ac.newVaultId()
        val key = ac.newVaultKey()
        val phrase = ac.newRecoveryPhrase()
        val data = Json.parseToJsonElement("""{"accounts":[{"id":"x","site":"Bänk ✓","password":"p\"q\n"}]}""")
        val out = buildJsonObject {
            put("username", "alex"); put("password", "pässwörd ﬁ")
            put("secret_key_text", ac.formatSecretKey(secret)); put("sealed_keyset", sealed)
            put("login_key", ac.b64(login))
            put("keyset", JsonObject(keyset.mapValues { JsonPrimitive(it.value) }))
            put("vault_id", ac.b64(id)); put("vault_key", ac.b64(key))
            put("wrapped", ac.wrapKey(ac.publicKeyOf(keyset), key, id, "alex"))
            put("blob", ac.encryptVault(key, id, 4, data)); put("data", data)
            put("sealed_name", ac.sealVaultName(key, id, "Wörk ✓"))
            put("phrase", phrase); put("sealed_recovery", ac.sealRecovery(phrase, "alex", keyset))
            put("signature", ac.sign(keyset, "hello".toByteArray()))
        }
        File("build/made-on-android.json").writeText(out.toString(), Charsets.UTF_8)
    }
}
