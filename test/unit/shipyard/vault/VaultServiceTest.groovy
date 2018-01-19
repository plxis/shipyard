package shipyard.vault

import shipyard.*
import org.junit.*
import groovy.mock.interceptor.MockFor
import com.bettercloud.vault.*
import com.bettercloud.vault.response.*

public class VaultServiceTest {
  Properties props

  @Before
  void before() {
    props = new Properties()
    props.vaultUrl = "xyz"
    props.vaultToken = "abc"
  }

  @After
  void after() {
    Archive.metaClass = null
  }

  def mockVault(Closure c) {
    def built
    def vc = new VaultConfig()
    def vcMock = new MockFor(VaultConfig)
    vcMock.demand.address { String url -> return vc }
    vcMock.demand.token { String token -> return vc }
    vcMock.demand.build { built = true; return vc }
    vcMock.use {
      def vs = new VaultService()
      vs.init(props)
      assert built
      c(vs)
    }      
  }

  def mockResult(data, statusCode = 200) {
    return new Object() {
      def getData(String key) {
        return data
      }
      def getRestResponse() {
        return new Object() {
          def getStatus() {
            return statusCode
          }
        }
      }
    } 
  }

  @Test(expected=StorageException)
  void test_init_missing_vault_url() {
    def vs = new VaultService()
    vs.init(new Properties())
  }

  @Test(expected=StorageException)
  void test_init_missing_vault_token() {
    def vs = new VaultService()
    def props = new Properties()
    props.vaultUrl = "xyz"
    vs.init(props)
  }

  @Test
  void test_init() {
    mockVault { vs ->
      assert vs instanceof VaultService
    }
  }

  @Test
  void test_list() {
    def actualPath
    def logical = new Object() {
      def list(String path) {
        actualPath = path
        return ["foo","bar"]
      }
    }
    def vMock = new MockFor(Vault)
    vMock.demand.logical { return logical }
    vMock.use {
      mockVault { vs ->
        def actual = vs.list("test/path")
        assert actual.size() == 2
        assert actual[0] == "foo"
        assert actual[1] == "bar"
        assert actualPath == "test/path"
      }
    }
  }

  @Test
  void test_readAll() {
    def actualPath
    def logical = new Object() {
      def read(String path) {
        actualPath = path
        return mockResult([foo:'bar'])
      }
    }
    def vMock = new MockFor(Vault)
    vMock.demand.logical { return logical }
    vMock.use {
      mockVault { vs ->
        def actual = vs.readAll("test/path")
        assert actual.foo == 'bar'
        assert actualPath == "test/path"
      }
    }
  }

  @Test
  void test_readKey() {
    def actualPath
    def logical = new Object() {
      def read(String path) {
        actualPath = path
        return mockResult([foo:'bar'])
      }
    }
    def vMock = new MockFor(Vault)
    vMock.demand.logical { return logical }
    vMock.use {
      mockVault { vs ->
        def actual = vs.readKey("test/path", "foo")
        assert actual == 'bar'
        assert actualPath == "test/path"
      }
    }
  }


  @Test
  void test_write() {
    def actualReadPath, actualWritePath, actualValues
    def logical = new Object() {
      def read(String path) {
        actualReadPath = path
        return mockResult([foo:'bar'])
      }
      def write(String path, Map values) {
        actualWritePath = path
        actualValues = values
        return mockResult(null)
      }
    }
    def vMock = new MockFor(Vault)
    vMock.demand.logical { return logical }
    vMock.demand.logical { return logical }
    vMock.use {
      mockVault { vs ->
        def actual = vs.write("test/path", "foo", "val")
        assert !actual
        assert actualValues == [foo:"val"]
        assert actualReadPath == "test/path"
        assert actualWritePath == "test/path"
      }
    }
  }

  @Test
  void test_write_new() {
    def actualReadPath, actualWritePath, actualValues
    def logical = new Object() {
      def read(String path) {
        actualReadPath = path
        return mockResult(null, 404)
      }
      def write(String path, Map values) {
        actualWritePath = path
        actualValues = values
        return mockResult(null)
      }
    }
    def vMock = new MockFor(Vault)
    vMock.demand.logical { return logical }
    vMock.demand.logical { return logical }
    vMock.use {
      mockVault { vs ->
        def actual = vs.write("test/path", "foo", "val")
        assert !actual
        assert actualValues == [foo:"val"]
        assert actualReadPath == "test/path"
        assert actualWritePath == "test/path"
      }
    }
  }  

  @Test(expected=StorageException)
  void test_write_fail() {
    def actualReadPath, actualWritePath, actualValues
    def logical = new Object() {
      def read(String path) {
        actualReadPath = path
        return mockResult(null, 404)
      }
      def write(String path, Map values) {
        actualWritePath = path
        actualValues = values
        return mockResult(null, 500)
      }
    }
    def vMock = new MockFor(Vault)
    vMock.demand.logical { return logical }
    vMock.demand.logical { return logical }
    vMock.use {
      mockVault { vs ->
        def actual = vs.write("test/path", "foo", "val")
      }
    }
  }  

  @Test
  void test_write_with_backup() {
    def logical = new Object() {
      def read(String path) {
        return mockResult([foo:'bar'])
      }
      def write(String path, Map values) {
        return mockResult(null)
      }
    }
    def vMock = new MockFor(Vault)
    vMock.demand.logical { return logical }
    vMock.demand.logical { return logical }

    def zipFile = new File("test.zip")
    def foundZipDir, foundArchiveDir, foundInput, foundPruneDir, foundMaxAgeInDays, foundUser, foundHost, foundPort, foundDir, foundArchive

    def archiveMock = new MockFor(Archive) 
    archiveMock.demand.zip { String dir -> foundZipDir=dir; return zipFile }
    archiveMock.demand.archiveFile { File input, String archiveDir -> foundInput = input; foundArchiveDir = archiveDir; return zipFile }
    archiveMock.demand.pruneArchiveFiles { String archiveDir, int maxAgeInDays -> foundPruneDir = archiveDir; foundMaxAgeInDays = maxAgeInDays }
    archiveMock.demand.upload { File archive, String user, String host, int port, String dir -> foundArchive = archive; foundUser = user; foundHost=host; foundPort=port; foundDir=dir }

    def fileMock = new MockFor(File)
    fileMock.demand.isDirectory(2) { return true }

    vMock.use {
      fileMock.use {
        archiveMock.use {
          props.vaultDataDir = "/tmp/test"
          props.vaultArchiveDir = "/tmp/archive"
          props.vaultArchiveMaxAgeInDays = "10"
          props.vaultArchiveRemoteUser = "user"
          props.vaultArchiveRemoteHost = "host"
          props.vaultArchiveRemotePort = "22"
          props.vaultArchiveRemoteDir = "dir"
          mockVault { vs ->
            vs.write("test/path", "foo", "val")
            assert foundZipDir == props.vaultDataDir
            assert foundArchiveDir == props.vaultArchiveDir
            assert foundInput == zipFile
            assert foundPruneDir == props.vaultArchiveDir
            assert foundMaxAgeInDays == 10
            assert foundArchive == zipFile
            assert foundUser == "user"
            assert foundHost == "host"
            assert foundPort == 22
            assert foundDir == "dir"
          }
        }
      }
    }
  }

  @Test
  void test_write_with_backup_invalid_dir() {
    def logical = new Object() {
      def read(String path) {
        return mockResult([foo:'bar'])
      }
      def write(String path, Map values) {
        return mockResult(null)
      }
    }
    def vMock = new MockFor(Vault)
    vMock.ignore.logical { return logical }

    def fileMock = new MockFor(File)
    fileMock.demand.isDirectory { return false }
    fileMock.demand.isDirectory { return true }
    fileMock.demand.isDirectory { return false }

    vMock.use {
      fileMock.use {
        props.vaultDataDir = "/tmp/test"
        props.vaultArchiveDir = "/tmp/archive"
        mockVault { vs ->
          try {
            vs.write("test/path", "foo", "val")
            assert false: "should have thrown exception"
          } catch (StorageException se) {
            assert se.message.contains("vaultDataDir")
          }
          try {
            vs.write("test/path", "foo", "val")
            assert false: "should have thrown exception"
          } catch (StorageException se) {
            assert se.message.contains("vaultArchiveDir")
          }
        }
      }
    }
  }

  @Test(expected=StorageException)
  void should_fail_restore_if_backup_file_not_found() {
    props.vaultDataDir = "/tmp/vault/store"
    props.vaultArchiveDir = "/tmp/vault/backup"

    def vMock = new MockFor(Vault)

    def fileMock = new MockFor(File)
    fileMock.demand.exists { return false }

    vMock.use {
      fileMock.use {
        mockVault { vs ->
          vs.restore("last-good-backup.zip")
        }
      }
    }
  }

  @Test
  void should_restore_from_backup_file() {
    props.vaultDataDir = "/tmp/vault/store"
    props.vaultArchiveDir = "/tmp/vault/backup"

    def vs = new VaultService()
    vs.init(props)

    def fileMock = new MockFor(File)
    fileMock.demand.exists { return true }

    def unzipFile
    def unzipDir

    Archive.metaClass.unzip = { String file, String dir -> 
      unzipFile = '/tmp/vault/backup/last-good-backup.zip'
      unzipDir = '/tmp/vault/store'
    }

   fileMock.use {
      vs.restore("last-good-backup.zip")
    }

    assert unzipFile == '/tmp/vault/backup/last-good-backup.zip'
    assert unzipDir == '/tmp/vault/store'
  }
}