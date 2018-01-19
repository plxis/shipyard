package shipyard.command

import shipyard.*
import org.junit.*
import groovy.mock.interceptor.MockFor

public class RestoreTest {
  @Test
  public void should_show_usage() {
    def cmd = new Restore()
    assert cmd.description
    assert cmd.args.find { it.name == "file" } 
  }

  @Test
  public void should_require_lock() {
    assert new Restore().shouldLock()
  }

  @Test
  public void should_restore_from_backup() {
    def foundFile
    def props = new Properties()
    props.file = "backup.zip"

    def svc = new Object() {
      void restore(String filename) {foundFile = filename }
    }

    def restore = new Restore()
    restore.execute(svc as StorageService, props)

    assert foundFile == 'backup.zip'
  }
}