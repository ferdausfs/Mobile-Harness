package com.jarves.mh.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The no-silent-overwrite contract of [ApiKeyVault.put], exercised through
 * the pure [resolvePut] decision function (the vault itself needs
 * AndroidKeyStore, which is not available on the JVM).
 */
class ApiKeyVaultPutTest {

    @Test
    fun emptyPoolCreatesThePrimaryEntry() {
        assertEquals(PutAction.AddEntry("Primary"), resolvePut(poolSize = 0, activeSecret = null, secret = "key-1"))
    }

    @Test
    fun storingTheActiveSecretAgainIsAnIdempotentNoOp() {
        assertEquals(PutAction.NoOp, resolvePut(poolSize = 1, activeSecret = "key-1", secret = "key-1"))
        assertEquals(PutAction.NoOp, resolvePut(poolSize = 3, activeSecret = "key-1", secret = "key-1"))
    }

    @Test
    fun differentSecretBecomesANewEntryAndNeverOverwrites() {
        // Previously put() silently replaced the active entry's secret under
        // its existing name; it must add a new entry instead.
        assertEquals(PutAction.AddEntry("API key 2"), resolvePut(poolSize = 1, activeSecret = "key-1", secret = "key-2"))
        assertEquals(PutAction.AddEntry("API key 4"), resolvePut(poolSize = 3, activeSecret = "key-1", secret = "key-2"))
    }

    @Test
    fun unreadableActiveSecretFallsBackToAddingANewEntry() {
        // A key that cannot be decrypted anymore must not be silently
        // replaced either - the new secret gets its own entry.
        assertEquals(PutAction.AddEntry("API key 2"), resolvePut(poolSize = 1, activeSecret = null, secret = "key-2"))
    }
}
