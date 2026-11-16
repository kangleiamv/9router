package ai.ninerouter.core

import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Self-signed TLS material for a hosted site.
 *
 * Certificates are generated on-device with the bundled PHP OpenSSL extension
 * and stored inside the service data folder, so they survive redeploys and are
 * never exposed through the web root. A Node/Static release can load them with
 * `process.env.TLS_CERT` and `process.env.TLS_KEY` to serve HTTPS on loopback;
 * public HTTPS is still terminated by Cloudflare at the tunnel edge.
 */
object SiteTls {
    const val DIR = "tls"
    const val CERT_FILE = "cert.pem"
    const val KEY_FILE = "key.pem"
    const val DEFAULT_DAYS = 825

    data class Certificate(
        val subject: String,
        val issuer: String,
        val notAfter: Long,
        val sha256: String,
        val names: List<String>,
    ) {
        val expired: Boolean get() = notAfter <= System.currentTimeMillis()
        val daysLeft: Long
            get() = ((notAfter - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0L)
    }

    fun certFile(layout: HostLayout): File = File(File(layout.data, DIR), CERT_FILE)

    fun keyFile(layout: HostLayout): File = File(File(layout.data, DIR), KEY_FILE)

    /** True when a usable certificate pair is stored for [layout]. */
    fun installed(layout: HostLayout): Boolean =
        certFile(layout).isFile && keyFile(layout).length() > 0

    /** Parse a PEM certificate, returning null when it is missing or invalid. */
    fun read(file: File): Certificate? = runCatching {
        if (!file.isFile) return null
        val factory = CertificateFactory.getInstance("X.509")
        val cert = file.inputStream().use { factory.generateCertificate(it) } as X509Certificate
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
            .joinToString(":") { "%02X".format(it) }
        Certificate(
            subject = cert.subjectX500Principal.name,
            issuer = cert.issuerX500Principal.name,
            notAfter = cert.notAfter.time,
            sha256 = digest,
            names = cert.subjectAlternativeNames.orEmpty().mapNotNull { entry ->
                val list = entry as? List<*> ?: return@mapNotNull null
                when (list.firstOrNull()) {
                    2 -> list.getOrNull(1)?.toString()
                    7 -> list.getOrNull(1)?.toString()?.let { "IP $it" }
                    else -> null
                }
            },
        )
    }.getOrNull()

    /** Hostnames a certificate should cover for one site. */
    fun namesFor(service: HostedService): List<String> = buildList {
        val domain = service.domain.trim().lowercase()
        if (domain.isNotEmpty()) add(domain)
        add("${service.id}.localhost")
        add("localhost")
    }.distinct()

    /** Subject alternative name entry used by OpenSSL: DNS:a,DNS:b or IP:... */
    fun sanEntry(names: List<String>): String = names.joinToString(",") { name ->
        if (name.matches(Regex("[0-9.]+"))) "IP:$name" else "DNS:$name"
    }

    /** Minimal OpenSSL configuration, written next to the temporary key. */
    fun config(commonName: String, names: List<String>): String = buildString {
        appendLine("[req]")
        appendLine("prompt = no")
        appendLine("distinguished_name = dn")
        appendLine("x509_extensions = v3_req")
        appendLine("[dn]")
        appendLine("CN = $commonName")
        appendLine("O = 9Router on-device hosting")
        appendLine("[v3_req]")
        appendLine("subjectAltName = ${sanEntry(names)}")
        appendLine("basicConstraints = CA:FALSE")
        appendLine("keyUsage = digitalSignature, keyEncipherment")
        appendLine("extendedKeyUsage = serverAuth")
    }

    /**
     * PHP program used to create the pair. Paths and names arrive through the
     * environment so no user value is interpolated into code.
     */
    val GENERATE_SCRIPT: String = """
        ${'$'}cfg = getenv("TLS_CNF");
        ${'$'}cn = getenv("TLS_CN");
        ${'$'}key = openssl_pkey_new(["config" => ${'$'}cfg, "private_key_bits" => 2048, "private_key_type" => OPENSSL_KEYTYPE_RSA]);
        if (${'$'}key === false) { fwrite(STDERR, "Could not create a private key"); exit(1); }
        ${'$'}csr = openssl_csr_new(["commonName" => ${'$'}cn], ${'$'}key, ["config" => ${'$'}cfg, "digest_alg" => "sha256"]);
        if (${'$'}csr === false) { fwrite(STDERR, "Could not create a CSR"); exit(1); }
        ${'$'}cert = openssl_csr_sign(${'$'}csr, null, ${'$'}key, (int) getenv("TLS_DAYS"), ["config" => ${'$'}cfg, "digest_alg" => "sha256", "x509_extensions" => "v3_req"], random_int(1, 2147483647));
        if (${'$'}cert === false) { fwrite(STDERR, "Could not sign the certificate"); exit(1); }
        openssl_x509_export(${'$'}cert, ${'$'}certPem);
        openssl_pkey_export(${'$'}key, ${'$'}keyPem, null, ["config" => ${'$'}cfg]);
        file_put_contents(getenv("TLS_CERT_OUT"), ${'$'}certPem);
        file_put_contents(getenv("TLS_KEY_OUT"), ${'$'}keyPem);
        chmod(getenv("TLS_KEY_OUT"), 0600);
        echo "OK";
    """.trimIndent()
}
