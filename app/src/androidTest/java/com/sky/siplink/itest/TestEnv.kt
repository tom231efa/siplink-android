package com.sky.siplink.itest

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue

/**
 * Addresses and credentials of the test environment in testenv/. They are generated on the
 * developer machine and reach the tests as one instrumentation argument (see
 * testenv/run-tests.sh), so nothing secret is compiled into the test APK.
 */
class TestEnv private constructor(private val values: Map<String, String>) {
    /** Addresses of the PBX (SIP and AMI) and of the LDAP server, as the device reaches them. */
    val pbxHost: String get() = value("pbxHost")
    val ldapHost: String get() = value("ldapHost")

    /** Address of the scripted desk phones as the PBX sees them in their registrations. */
    val phonesHost: String get() = value("phonesHost")

    /** Where the device reaches the AMI of the scripted desk phones. */
    val phonesAmiHost: String get() = value("phonesAmiHost")
    val phonesAmiPort: Int get() = value("phonesAmiPort").toInt()

    /**
     * What the TLS certificate of the PBX is issued for: its DNS name, or its address when the
     * device has no name server that knows the name.
     */
    val pbxName: String get() = value("pbxName")

    /** DNS name in the certificate of the LDAP server; null when the device cannot resolve it. */
    val ldapName: String? get() = values["ldapName"]?.takeIf { it.isNotEmpty() }
    val sipPassword: String get() = value("sipPassword")
    val amiUser: String get() = value("amiUser")
    val amiSecret: String get() = value("amiSecret")
    val ldapBaseDn: String get() = value("ldapBaseDn")
    val ldapBindDn: String get() = value("ldapBindDn")
    val ldapPassword: String get() = value("ldapPassword")

    /** PEM of the private CA that signed the PBX and LDAP server certificate. */
    val caPem: ByteArray get() = Base64.decode(value("caPemBase64"), Base64.DEFAULT)

    private fun value(name: String): String =
        values[name] ?: throw AssertionError("'$name' is missing from the '$ARGUMENT' instrumentation argument")

    companion object {
        const val ARGUMENT = "siplinkEnv"

        /** Skips the test (instead of failing it) when the environment was not provided. */
        fun load(): TestEnv {
            val encoded = InstrumentationRegistry.getArguments().getString(ARGUMENT)
            assumeTrue("No test environment: run the tests through testenv/run-tests.sh", !encoded.isNullOrEmpty())
            val text = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            val values = text.lineSequence()
                .filter { '=' in it }
                .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
            return TestEnv(values)
        }
    }
}
