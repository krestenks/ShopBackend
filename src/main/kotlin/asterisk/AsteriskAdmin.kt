package asterisk

/**
 * Bundle of the Asterisk services the admin UI and mobile API need.
 * Present only when ASTERISK_ENABLED=true; null means "Twilio mode".
 */
class AsteriskAdmin(
    val config: AsteriskConfig,
    val amiClient: AmiClient,
    val provisioner: AsteriskProvisioner,
    val modemScanner: ModemScanner,
)
