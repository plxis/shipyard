package util

import org.junit.*
import static org.junit.Assert.*
import groovy.mock.interceptor.*
import com.jcraft.jsch.*

class SftpTest {

  @Test
  void should_mock_itself() {
    def file = FileData.sample()

    assert Sftp.mock() instanceof MockFor
    assert Sftp.mock([file]) instanceof MockFor
    assert Sftp.mock([file, file]) instanceof MockFor
  }

  @Test
  void should_list_mock_files() {
    def file = FileData.sample()
    
    def sftpMock = Sftp.mock([file])

    sftpMock.use {
      def sftp = Sftp.factory("un", "hn", 22)
      def files = sftp.ls()
      sftp.close()

      assert files == [file]
    }
  }

  @Test
  void should_returns_files_implementing_file_interface() {
    def sftpMock = Sftp.mock()
    sftpMock.use { 

      def sftp = Sftp.factory("un", "hn", 22)
      def file = sftp.get("from_file", "to_file")

      assert file.name == "to_file"
      assert file.size() == 123
      assert file.exists() == true
    }
  }

}
