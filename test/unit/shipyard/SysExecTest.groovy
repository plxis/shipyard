package shipyard

import shipyard.command.*
import shipyard.vault.*
import java.util.concurrent.Callable
import groovy.mock.interceptor.MockFor
import org.junit.*

public class SysExecTest {
  @Test
  void test_run() {
    def env = [:]
    def workDir
    int exitCode = 12
    def foundOutStr
    def foundErrStr
    def foundTimeout

    def proc = new Process() {
      int  exitValue() { return exitCode }
      void destroy() {}
      InputStream  getErrorStream() { null }
      InputStream  getInputStream() { null }
      OutputStream getOutputStream() { null }
      int  waitFor() { return exitCode }
      void waitForOrKill(long timeout) { foundTimeout = timeout }
      void consumeProcessErrorStream(InputStream ostr) { foundErrStr = istr }
      void consumeProcessOutputStream(OutputStream ostr) { foundOutStr = ostr }
    }
    def pbMock = new MockFor(ProcessBuilder)
    pbMock.demand.environment { return env }
    pbMock.demand.directory { File file -> workDir = file }
    pbMock.demand.start { return proc }

    pbMock.use {
      def exec = new SysExec()
      assert exitCode == exec.run(["cmd","arg1"],["var":"env1"],"/tmp/work",1000)
      assert foundTimeout == 1000
      assert foundErrStr == foundOutStr
      assert env.var == "env1"
    }
  }
}