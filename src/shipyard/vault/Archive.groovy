package shipyard.vault

import util.*
import java.nio.file.*
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.apache.commons.io.IOUtils

public class Archive {
  private static Log log = Log.getLogger(Archive)
  private static final String timestampFormat = "yyyyMMddHHmmssSSS"
  private static final String extension = ".zip"

  public Archive() {
  }

  public File zip(String dir) {
    File tempFile = File.createTempFile("vault-archive", ".zip");
    log.info("Creating zip file", [filename:tempFile.path,inputDir:dir])
    ZipOutputStream output = new ZipOutputStream(new FileOutputStream(tempFile))
    def dirFile = new File(dir)
    zipDir(output, dirFile, dirFile)
    output.close(); 
    log.info("Finished creating zip", [filename:tempFile.path,size:tempFile.size()])
    return tempFile
  }

  public void unzip(String file, String outputDir) {
    def zipFile = new ZipFile(file)

    try {
      def entries = zipFile.entries()

      while (entries.hasMoreElements()) {
        def entry = entries.nextElement()
        def entryDestination = new File(outputDir,  entry.name)

        if (entry.isDirectory()) {
          entryDestination.mkdirs()
        } 
        else {
          entryDestination.getParentFile().mkdirs()
          def ins = zipFile.getInputStream(entry)
          def out = new FileOutputStream(entryDestination)
          IOUtils.copy(ins, out)
          IOUtils.closeQuietly(ins)
          out.close()
        }
      }

    } finally {
      zipFile.close()
    }
  }

  public File archiveFile(File input, String archiveDir) {
    def time = new Date().format(timestampFormat)
    File output = new File(archiveDir, "vault-backup_${time}${extension}")
    if (output.isFile()) output.delete()
    log.info("Archiving file", [intputFile:input.path,archiveFile:output.path])
    moveFile(input.toPath(), output.toPath())
    return output
  }

  protected def moveFile(srcPath, targetPath) {
    // Attempt atomic move first
    try {
      Files.move(srcPath, targetPath, StandardCopyOption.ATOMIC_MOVE)
    } catch(AtomicMoveNotSupportedException e) {
      // If atomic move not supported, attempt standard move
      Files.move(srcPath, targetPath)
    }
  }

  public void pruneArchiveFiles(String archiveDir, int maxAgeInDays) {
    def file = new File(archiveDir) 
    file.eachFile { archive ->
      pruneArchiveFile(archive, maxAgeInDays) 
    }
  }

  public void pruneArchiveFile(File archive, int maxAgeInDays) {
    def tokens = archive.name.split("_")
    if (tokens.size() > 1) {
      def timestamp = tokens[1] - extension
      def date = Date.parse(timestampFormat, timestamp)
      def expirationDate = now() - maxAgeInDays
      if (date < expirationDate) {
        log.info("Deleting old archive file", [archiveFile:archive.path,timestamp:timestamp,maxAgeInDays:maxAgeInDays])
        archive.delete()
      }
    } else {
      log.warn("Discovered unexpected file in archive", [file:archive.name])
    }
  }

  public upload(File archive, String user, String host, int port, String destDir) {
    log.info("Uploading archive to remote host", [archive:archive.path,user:user,host:host,port:port,destDir:destDir])
    def sftp = Sftp.factory(user, host, port)
    try {
      sftp.put(archive.path, destDir + "/" + archive.name)
    }
    finally {
      sftp.close()
    }
  }

  protected Date now() {
    new Date()
  }

  protected void zipDir(ZipOutputStream output, File input, File rootDir) {
    input.eachFile() { file ->
      zipDirEntry(output, file, rootDir)
    }
  }

  protected void zipDirEntry(ZipOutputStream output, File input, File rootDir) {
    if (input.isDirectory()) {
      log.debug("Discovered subdirectory to include in zip", [subdir:input.path])
      zipDir(output, input, rootDir)
    } else {
      log.debug("Adding file to zip", [file:input.path,size:input.size()])
      output.putNextEntry(new ZipEntry(nameZipEntry(input, rootDir)))
      InputStream inputstr = new FileInputStream(input);
      Files.copy(inputstr, output);
      output.closeEntry();
      inputstr.close()
    }
  }

  protected String nameZipEntry(File file, File rootDir) {
    StringBuffer sb = new StringBuffer()
    sb.append(file.name)
    while (file && (file.parentFile != rootDir)) {
      sb.insert(0, File.separator)
      sb.insert(0, file.parentFile.name)
      file = file.parentFile
    }
    return sb.toString()
  }
}