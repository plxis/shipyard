package shipyard.vault

import org.junit.*
import groovy.mock.interceptor.MockFor
import java.nio.file.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import util.*

public class ArchiveTest {
  @After
  void after() {
    Archive.metaClass = null
    Sftp.metaClass = null
  }

  @Test
  public void should_zip() {
    def foundPrefix, foundSuffix, foundOutput, foundInput, foundRootDir
    def tmpFile = new File("temp.file")
    def fileMock = new MockFor(File)
    fileMock.demand.createTempFile { String prefix, String suffix -> foundPrefix=prefix; foundSuffix=suffix; return tmpFile }
    fileMock.ignore.getPath { return "/tmp/temp.file" }
    fileMock.ignore.size { return 1 }
    
    def archive = new Archive() {
      public void zipDir(ZipOutputStream output, File input, File rootDir) {
        foundOutput = output
        foundInput = input
        foundRootDir = rootDir
      }
    }

    fileMock.use {
      assert archive.zip("/tmp/dir") == tmpFile
    }

    tmpFile.delete()
    assert foundPrefix == "vault-archive"
    assert foundSuffix == ".zip"
    assert foundOutput
    assert foundInput
    assert foundRootDir
  }

  @Test
  public void should_zip_dir_entry_for_directory() {
    def foundOutput, foundInput, foundRootDir, foundEntry
    boolean alt = false
    def fileMock = new MockFor(File)
    fileMock.demand.isDirectory { true }
    fileMock.demand.getPath { "mypath" }
    fileMock.ignore.size { 1 }

    def archive = new Archive() {
      public void zipDir(ZipOutputStream out, File input, File rootDir) {
        foundOutput = out
        foundInput = input
        foundRootDir = rootDir
      }
    }

    def output = new ZipOutputStream(new ByteArrayOutputStream()) {
      public void putNextEntry(ZipEntry e) {
        foundEntry = e
      }
    }

    fileMock.use {
      archive.zipDirEntry(output, new File("/tmp/dir/childDir"), new File("/tmp/dir"))
    }

    assert foundOutput
    assert foundInput
    assert foundRootDir
    assert !foundEntry
  }

  @Test
  public void should_zip_dir_entry_for_file() {
    def foundEntry, closedEntry, copied, foundOutput, foundInput, foundRootDir
    boolean alt = false
    def fileMock = new MockFor(File)
    fileMock.demand.isDirectory { false }
    fileMock.demand.getPath { "mypath" }
    fileMock.ignore.size { 1 }

    def output = new ZipOutputStream(new ByteArrayOutputStream()) {
      public void putNextEntry(ZipEntry e) {
        foundEntry = e
      }
      public void closeEntry() {
        closedEntry = true
      }
    }

    def archive = new Archive() {
      public void zipDir(ZipOutputStream out, File input, File rootDir) {
        foundOutput = out
        foundInput = input
        foundRootDir = rootDir
      }
      protected String nameZipEntry(File file, File rootDir) { "test/file" }
    }

    def filesMock = new MockFor(Files)
    filesMock.demand.copy { InputStream i, OutputStream o -> copied = true }

    def tmpFile = File.createTempFile("ArchiveTest_should_zip_dir_entry_for_file", ".tmp")
    fileMock.use {
      filesMock.use {
        archive.zipDirEntry(output, tmpFile, new File("/tmp/dir"))
      }
    }

    tmpFile.delete()

    assert !foundOutput
    assert !foundInput
    assert !foundRootDir
    assert foundEntry?.name == "test/file"
    assert copied
    assert closedEntry
  }

  @Test
  void should_name_zip_entry() {
    def tmpFile = File.createTempFile("ArchiveTest_should_name_zip_entry", ".tmp")
    def archive = new Archive()
    def noParent = archive.nameZipEntry(tmpFile, tmpFile.parentFile) 
    def hasParent = archive.nameZipEntry(tmpFile, null) 
    tmpFile.delete()
    assert noParent == tmpFile.name
    assert hasParent.endsWith("/" + tmpFile.name)
  }

  @Test
  void should_unzip_file() {
    def archive = new Archive()

    def tmpSuffix = "test." + System.currentTimeMillis()
    def testDir    = "/tmp/vault.store.${tmpSuffix}"
    def restoreDir = "/tmp/vault.backup.${tmpSuffix}"
    
    def originalFiles = []
    def restoredFiles = []

    try {
      new File(testDir, "foo/bar/baz").mkdirs()
      new File(restoreDir).mkdirs()
  
      new File(testDir, "file1").write('foo')
      new File(testDir + "/foo", "file2").write('foo')
      new File(testDir + "/foo/bar", "file3").write('foo')
      new File(testDir + "/foo/bar/baz", "file4").write('foo')
      new File(testDir).eachFileRecurse { println it; originalFiles << it.path - testDir }
      
      def zipFile = archive.zip(testDir)
      archive.unzip(zipFile.path, restoreDir)
      zipFile.delete()

      new File(restoreDir).eachFileRecurse { println it; restoredFiles << it.path - restoreDir }
    }

    finally {
      new File(testDir).deleteDir()
      new File(restoreDir).deleteDir()
    }

    assert restoredFiles == originalFiles
  }

  @Test
  void should_archive_file() {
    def foundPattern, foundOptions, foundInput, foundOutput
    def inputPath = new File("input.file").toPath()
    def outputPath = new File("output.file").toPath()
    def dateMock = new MockFor(Date)
    dateMock.demand.format { String pattern -> foundPattern=pattern; return "20170106113411001" }

    def fileMock = new MockFor(File)
    fileMock.demand.isFile { false }
    fileMock.ignore.getPath { "somewhere" }
    fileMock.demand.toPath { inputPath }
    fileMock.demand.toPath { outputPath }

    def filesMock = new MockFor(Files)
    filesMock.demand.move { Path input, Path output, CopyOption... options -> foundInput = input; foundOutput = output; foundOptions = options }
 
    dateMock.use {
      fileMock.use {
        filesMock.use {
          def archive = new Archive()
          assert archive.archiveFile(new File("test.file"), "/parent") != null
        }
      }
    }
    assert foundPattern == "yyyyMMddHHmmssSSS"
    assert foundInput == inputPath
    assert foundOutput == outputPath
    assert foundOptions.contains(StandardCopyOption.ATOMIC_MOVE)
  }

  @Test
  void should_fallback_to_non_atomic_move_when_necessary() {
    def filesMock = new MockFor(Files)
    def moveOptions
    def calledWithoutOptions = false
    filesMock.demand.move(2) { Path input, Path output, CopyOption... options ->
      if(options) {
        moveOptions = options
        if(options == [StandardCopyOption.ATOMIC_MOVE]) {
          throw new AtomicMoveNotSupportedException(input.toString(), output.toString(), "test")
        }
      } else {
        calledWithoutOptions = true
      }
      output
    }

    def archive = new Archive()
    filesMock.use {
      archive.moveFile(new File("src").toPath(), new File("dst").toPath())
    }
    assert moveOptions == [StandardCopyOption.ATOMIC_MOVE]
    assert calledWithoutOptions == true
  }

  @Test
  void should_prune_archive_file() {
    int deleteCount = 0
    def fileMock = new MockFor(File)
    fileMock.ignore.getName { "vault-backup_20160106121314156.zip" }
    fileMock.ignore.getPath { "somewhere" }
    fileMock.ignore.delete { deleteCount++ }

    def date = Date.parse("yyyyMMdd", "20160201")

    Archive.metaClass.now = { date }

    fileMock.use {
      def archive = new Archive() 
      archive.pruneArchiveFile(new File("test.file"), 10)
      archive.pruneArchiveFile(new File("test.file"), 15)
      archive.pruneArchiveFile(new File("test.file"), 20)
      archive.pruneArchiveFile(new File("test.file"), 25)
      archive.pruneArchiveFile(new File("test.file"), 30)
    }

    assert deleteCount == 4
  }

  @Test
  void should_upload() {
    def foundInput, foundOutput, closed
    Sftp.metaClass.'static'.factory = { user, host, port -> return new Object() {
        def put(input, output) {
          foundInput = input
          foundOutput = output
        }
        def close() {
          closed = true
        }
      }
    }
    new Archive().upload(new File("test.zip"), "user", "host", 22, "dir")
    assert closed
  }
}