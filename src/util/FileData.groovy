package util

import java.nio.file.*
import groovy.transform.*

@EqualsAndHashCode()
class FileData implements Cloneable {
  static sample(attrs = [:]) {
    def baseAttrs = [
        path: "/tmp",
        filename: "file1-11111213141516.csv",
        accessTime: DateTime.parse("1111-12-13 14:15:17"),
        modifyTime: DateTime.parse("1111-12-13 14:15:16"),
        flags: 14,
        groupId: 1,
        permissions: 16877, // 100000111101101 == drwxr-xr-x.reverse()
        size: 234L,
        isDirectory: false,
        isLink: false]

    new FileData(baseAttrs + attrs)
  }

  // Product that produced the referenced file.
  def product
  // Data definition type of the file contents.
  def entityType
  // Directory location of the file.
  def path

  String filename
  def accessTime
  def flags
  def groupId
  def modifyTime
  def permissions
  def size
  // true if its a directory
  def isDirectory
  // true if its a link
  def isLink

  /*
   * http://epaul.github.io/jsch-documentation/javadoc/com/jcraft/jsch/SftpATTRS.html
   * Fields from LsEntry that are not used yet.
   * - lsEntry.attrs.extended
   * - lsEntry.attrs.atimeString
   * - lsEntry.attrs.mtimeString
   * - lsEntry.attrs.permissionsString
   */
  static fromLsEntry(lsEntry) {
    def fileData = new FileData()
    
    fileData.filename = lsEntry.filename
    fileData.accessTime = lsEntry.attrs.ATime
    fileData.flags = lsEntry.attrs.flags
    fileData.groupId = lsEntry.attrs.gid
    fileData.modifyTime = lsEntry.attrs.mtime
    fileData.permissions = lsEntry.attrs.permissions
    fileData.size = lsEntry.attrs.size
    fileData.isDirectory = lsEntry.attrs.dir
    fileData.isLink = lsEntry.attrs.link

    fileData
  }

  static fromPath(path) {
    if (!path.contains("/")) path = "./${path}"
    path = Paths.get(path)

    def fileData = new FileData()

    fileData.path = path.parent.toString()
    fileData.filename = path.fileName.toString()
    fileData.accessTime = null
    fileData.flags = null
    fileData.groupId = null
    fileData.modifyTime = null
    fileData.permissions = null
    fileData.size = null
    fileData.isDirectory = false
    fileData.isLink = null

    fileData
  }

  static factory(options) {
    new FileData(options)
  }

  private FileData(options) {
    options?.each { k, v -> this[k] = v }

    if (path == null || path == "") path = "."
  }

  // Assuming date time format is yyyyMMddHHmmss GMT. The date time of the files creation is embedded in
  // the filename.
  def getFilenameDateTime() {
    def dateTime = false

    def group = (filename =~ /.*([12][0-9]{3}[0-1][0-9][0-3][0-9][0-1][0-9][0-5][0-9][0-5][0-9]).*/)
    if (group.matches()) {
      dateTime = DateTime.parse("${group[0][1]} -0000", "yyyyMMddHHmmss zzzzz")
    }

    dateTime
  }

  /*
   * Add the modify time of the file to the filename.
   */
  def injectModifyTime() {
    if (false == filenameDateTime) {

      def group = (filename =~ /(.*)\.([^.]+)/)
      if (group.matches()) {
        def modifyDateTime   = DateTime.format(modifyTime,       "yyyyMMddHHmmss")
        def (name, suffix) = [group[0][1], group[0][2]]

        filename = "${name}-${modifyDateTime}.${suffix}"
      }
    }

    this
  }

  /*
   * Provide a sort key that can be used to sort by create date.
   */
  def sortKey() {
    def filenameDateTime = DateTime.format(filenameDateTime, "yyyyMMddHHmmss")
    def modifyDateTime   = DateTime.format(modifyTime,       "yyyyMMddHHmmss")
    "${filenameDateTime}|${modifyDateTime}"
  }

  def toFile() {
    if (!path && !filename) throw new IllegalArgumentException("Can not convert to file when path and filename is empty.")
    if (path && filename) {
      new File(path, filename)
    }
    else {
      path = path ?: filename
      new File(path)
    }
  }

  def toPath() {
    if (path == null || !filename) throw new IllegalArgumentException("Can not convert to path when path or filename is empty.")
    Paths.get(path, filename)
  }

  String toLongString() {
    "${product}, ${entityType}, ${path}, ${filename}, ${accessTime}, ${flags}, ${groupId}, ${modifyTime}, ${permissions}, ${size}, ${isDirectory}, ${isLink}" 
  }

  String toString() {
    toPath().toString()
  }
}
