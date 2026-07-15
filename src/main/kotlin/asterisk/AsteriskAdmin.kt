package asterisk

/** Bundle of the Asterisk services the admin UI and mobile API need. */
class AsteriskAdmin(
    val config: AsteriskConfig,
    val amiClient: AmiClient,
    val provisioner: AsteriskProvisioner,
    val modemScanner: ModemScanner,
)
