package util

import java.nio.file.*
import org.junit.*
import static org.junit.Assert.*
import org.junit.rules.ExpectedException
import groovy.mock.interceptor.*

class FileDataTest {
  @Test
  void should_from_ls_entry() {
    def lsEntry = [
      filename: "aFileName",
      attrs: [
        ATime: "anAccessTime",
        atimeString: "ignored",
        flags: "someFlags",
        gid: "aGroupId",
        mtime: "theModifyTime",
        mtimeString: "ignored",
        permissions: "thePermissions",
        permissionsString: "ignored",
        size: "theSize",
        dir: "isItADir",
        link: "isItALink",
      ]
    ]

    def file = FileData.fromLsEntry(lsEntry)

    assert file.path == "."
    assert file.filename == "aFileName"
    assert file.accessTime == "anAccessTime"
    assert file.flags == "someFlags"
    assert file.groupId == "aGroupId"
    assert file.modifyTime == "theModifyTime"
    assert file.permissions == "thePermissions"
    assert file.size == "theSize"
    assert file.isDirectory == "isItADir"
    assert file.isLink == "isItALink"
  }

  @Test
  void should_from_path() {
    def file = FileData.fromPath("/aa/bb/cc/dd/file.csv")

    assert file.path == "/aa/bb/cc/dd"
    assert file.filename == "file.csv"
    assert file.accessTime == null
    assert file.flags == null
    assert file.groupId == null
    assert file.modifyTime == null
    assert file.permissions == null
    assert file.size == null
    assert file.isDirectory == false
    assert file.isLink == null
  }

  @Test
  void should_from_path_when_only_a_file() {
    def file = FileData.fromPath("b.csv")

    assert file.path == "."
    assert file.filename == "b.csv"
    assert file.accessTime == null
    assert file.flags == null
    assert file.groupId == null
    assert file.modifyTime == null
    assert file.permissions == null
    assert file.size == null
    assert file.isDirectory == false
    assert file.isLink == null
  }

  @Test
  void should_produce_a_toString_like_an_ls() {
    def file = FileData.factory(filename: "aFileName") 

    assert file.toString() == "./aFileName"
  }

  @Test
  void should_sample() {
    assert FileData.sample().filename == "file1-11111213141516.csv"
    assert FileData.sample(filename: "fred").filename == "fred"
    assert FileData.sample(isDirectory: true).isDirectory == true
  }

  @Test
  void should_extract_the_datetime_from_the_filename() {

    def expectedDateTime = DateTime.parse("1111-12-13T10:15:16.0000000-04:00")

    assert FileData.sample(filename: "file-11111213141516.csv").filenameDateTime == expectedDateTime
    assert FileData.sample(filename: "file.11111213141516").filenameDateTime == expectedDateTime
    assert FileData.sample(filename: "a11111213141516").filenameDateTime == expectedDateTime
    assert FileData.sample(filename: "11111213141516").filenameDateTime == expectedDateTime

    assert FileData.sample(filename: "no_date").filenameDateTime == false
    assert FileData.sample(filename: "file-1111-12-13.14-15-16.csv").filenameDateTime == false
  }

  @Test
  void should_compare_by_datetime_in_filename_and_then_modification_datetime() {
    // Equal
    assert checkSortOrder("file1.csv"                , "1111-12-13 14:15:16" , "file2.csv"                , "1111-12-13 14:15:16") == 0

    // File 1 greater
    assert checkSortOrder("file1.csv"                , "1111-12-13 14:15:59" , "file2.csv"                , "1111-12-13 14:15:00") > 0
    assert checkSortOrder("file1.11111213141559.csv" , "1111-12-13 14:15:16" , "file2.11111213141500.csv" , "1111-12-13 14:15:16") > 0

    // File 2 greater
    assert checkSortOrder("file1.csv"                , "1111-12-13 14:15:00" , "file2.csv"                , "1111-12-13 14:15:59") < 0
    assert checkSortOrder("file1.11111213141500.csv" , "1111-12-13 14:15:16" , "file2.11111213141559.csv" , "1111-12-13 14:15:16") < 0
  }

  private checkSortOrder(filename1, modifyTime1, filename2, modifyTime2) {
    modifyTime1 = DateTime.parse(modifyTime1)
    modifyTime2 = DateTime.parse(modifyTime2)

    def file1 = FileData.sample(filename: filename1, modifyTime: modifyTime1)    
    def file2 = FileData.sample(filename: filename2, modifyTime: modifyTime2)    

    file1.sortKey() <=> file2.sortKey()
  }

  @Test
  void should_clone_itself() {
    def fileData = FileData.sample()

    assert fileData.clone() == FileData.sample()
  }

  @Test
  void should_compare_equal() {
    assert FileData.sample() == FileData.sample()
    assert FileData.sample().equals(FileData.sample())
    assert FileData.sample().hashCode() == FileData.sample().hashCode()

    def file1 = FileData.sample(filename: "file1") 
    def file1a = FileData.sample(filename: "file1") 
    def file2 = FileData.sample(filename: "file2")

    assert file1 == file1
    assert file1 == file1a

    assert file1 != file2
  }

  @Test
  void should_convert_to_path_interface() {
    assert FileData.sample().toPath() == Paths.get("/tmp/file1-11111213141516.csv")

    assert FileData.sample(path: "").toPath() == Paths.get("./file1-11111213141516.csv")
    assert FileData.sample(path: ".").toPath() == Paths.get("./file1-11111213141516.csv")
    assert FileData.sample(path: "/").toPath() == Paths.get("/file1-11111213141516.csv")
    assert FileData.sample(path: "/tmp/").toPath() == Paths.get("/tmp/file1-11111213141516.csv")
    assert FileData.sample(path: "/tmp/", filename: "//file1").toPath() == Paths.get("/tmp/file1")
  }

  @Test(expected = IllegalArgumentException)
  void should_throw_if_the_path_and_filename_is_invalid() {
    FileData.sample(path: null, filename: null).toPath()
  }

  @Test(expected = IllegalArgumentException)
  void should_throw_if_the_filename_only_is_invalid() {
    FileData.sample(path: "/tmp", filename: null).toPath()
  }

  @Test
  void should_convert_to_file_instance() {
    assert FileData.sample(path: "/tmp", filename: "file").toFile() == new File("/tmp/file")
  }

  @Test
  void should_convert_to_file_if_no_filename() {
    assert FileData.sample(path: "/tmp", filename: null).toFile() == new File("/tmp")
  }

  @Test
  void should_convert_to_file_if_no_path() {
    assert FileData.sample(path: null, filename: "file").toFile() == new File("./file")
  }

  @Test
  void should_add_the_modify_time_to_the_filename() {
    def filename = FileData.sample(filename: "file.csv", modifyTime: DateTime.parse("1111-12-13 14:15:16"))

    assert filename.injectModifyTime().filename == "file-11111213141516.csv"
  }

  @Test
  void should_not_add_a_modify_time_if_already_has_one_or_has_no_suffix() {
    assert FileData.sample(filename: "file.11111213141516.csv").injectModifyTime().filename ==  "file.11111213141516.csv"
    assert FileData.sample(filename: "file-11111213141516.csv").injectModifyTime().filename ==  "file-11111213141516.csv"
    assert FileData.sample(filename: "nosuffix").injectModifyTime().filename == "nosuffix"
  }
}
