package shipyard.vault

import shipyard.*
import util.*
import com.bettercloud.vault.*
import com.bettercloud.vault.response.*
import java.net.HttpURLConnection

public class VaultService implements StorageService {
  private static Log log = Log.getLogger(VaultService)
  private VaultConfig config
  private Vault vault
  private Properties props

  public VaultService() {}

  public void init(Properties props) {
    this.props = props
    config = new VaultConfig()
    if (!props?.vaultUrl) throw new StorageException("vaultUrl property is missing from shipyard configuration")
    config = config.address(props.vaultUrl)
    if (!props?.vaultToken) throw new StorageException("vaultToken property is missing from shipyard configuration")
    config = config.token(props.vaultToken)
    if (props.vaultOpenTimeout) config = config.openTimeout(props.vaultOpenTimeout.toInteger())
    if (props.vaultReadTimeout) config = config.readTimeout(props.vaultReadTimeout.toInteger())
    if (props.vaultSslCertPath) config = config.sslPemFile(props.vaultSslCertPath)
    if (props.vaultSslVerify) config = config.sslVerify(props.vaultSslVerify?.toBoolean())
    config = config.build()
    vault = new Vault(config)
    log.info("VaultService initialized successfully")
  }

  public void delete(String path) {
    log.info("Deleting vault path", [path:path])
    def result
    try {
      result = vault.logical().delete(path)
    } catch (VaultException ve) {
      throw new StorageException("Unable to delete from vault", ve.getHttpStatusCode(), ve)
    }
    reviewResult(result)
  }

  public List list(String path) {
    log.info("Listing vault path", [path:path])
    try {
      vault.logical().list(path)
    } catch (VaultException ve) {
      throw new StorageException("Unable to list keys in vault", ve.getHttpStatusCode(), ve)
    }
  }

  public Map readAll(String path) {
    log.info("Reading vault path", [path:path])
    def result
    try {
      result = vault.logical().read(path)
    } catch (VaultException ve) {
      if (ve.httpStatusCode == 404) {
        log.warn("No data defined yet; path=${path}")
        return [:]
      } else {  
        throw new StorageException("Unable to read from vault", ve.getHttpStatusCode(), ve)
      }
    }
    reviewResult(result)
  }

  public String readKey(String path, String key) {
    readAll(path)?.get(key)
  }

  public void write(String path, String key, Object value) {
    def map = [:]
    try {
      map = readAll(path)
    } catch (Exception e) {
      // Ignore since this would indicate the path hasn't yet been set to a value
    }
    map[key] = value
    write(path, map)
  }

  public void write(String path, Object values) {
    log.info("Writing vault path", [path:path,valuesSize:values.size()])

    def result
    try {
      result = vault.logical().write(path, values)
    } catch (VaultException ve) {
      throw new StorageException("Unable to write to vault", ve.getHttpStatusCode(), ve)
    }
    reviewResult(result)
    backup()
  }

  public void restore(String filename) {
    log.info("Attempting vault restore", [filename:filename, dataDir:props?.vaultDataDir, archiveDir:props?.vaultArchiveDir])

    if (!new File(props?.vaultArchiveDir, filename).exists())
      throw new StorageException("Backup file not found: ${props.vaultArchiveDir}/${filename}")

    def archive = new Archive()
    archive.unzip("${props.vaultArchiveDir}/${filename}", props.vaultDataDir)

    log.info("Restore succeeded", [file:filename, dataDir:props.vaultDataDir])
  }

  protected Map reviewResult(def result) {
    def code = result?.getRestResponse()?.getStatus()
    log.info("Received response for previous operation", [resultCode:code])
    if (!(code in [HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_NO_CONTENT])) {
      throw new StorageException("Unable to complete vault operation; resultCode=${code}")
    }
    return result.getData()
  }

  protected void backup() {
    log.info("Evaluating vault backup configuration", [vaultDataDir:props.vaultDataDir,vaultArchiveDir:props?.vaultArchiveDir,vaultArchiveMaxAgeInDays:props.vaultArchiveMaxAgeInDays])
    if (props?.vaultDataDir && props.vaultArchiveDir) {
      if (!new File(props.vaultDataDir).isDirectory()) throw new StorageException("Invalid vaultDataDir specified: ${props.vaultDataDir}")
      if (!new File(props.vaultArchiveDir).isDirectory()) throw new StorageException("Invalid vaultArchiveDir specified: ${props.vaultArchiveDir}")

      log.info("Vault archive settings are available, proceeding with vault backup")
      // Archive the data directory
      def archive = new Archive()
      def zipFile = archive.zip(props.vaultDataDir)
      zipFile = archive.archiveFile(zipFile, props.vaultArchiveDir)
      if (props.vaultArchiveMaxAgeInDays) {
        archive.pruneArchiveFiles(props.vaultArchiveDir, props.vaultArchiveMaxAgeInDays?.toInteger())
      }
      if (props.vaultArchiveRemoteUser && props.vaultArchiveRemoteHost && props.vaultArchiveRemoteHost && props.vaultArchiveRemoteDir) {
        archive.upload(zipFile, props.vaultArchiveRemoteUser, props.vaultArchiveRemoteHost, props.vaultArchiveRemotePort?.toInteger(), props.vaultArchiveRemoteDir)
      }
    } else {
      log.warn("Not backing up vault due to configuration settings", [vaultDataDir:props?.vaultDataDir,vaultArchiveDir:props?.vaultArchiveDir])
    }
  }
}