package shipyard

import shipyard.command.*
import util.TimeoutException
import groovy.mock.interceptor.MockFor
import org.junit.*

public class SuperintendentAgentTest {
  
  def props

  @Before
  void before() {
    props = new Properties()
    props['lockTimeoutMs'] = 1000
    props['command'] = 'foo'
  }

  @After
  void after() {
    StorageServiceFactory.metaClass = null
    Command.metaClass = null
  }

  @Test
  void should_construct() {
    def agent = new SuperintendentAgent()
    assert agent.props == null
  }

  @Test
  void should_not_execute_command_if_args_are_invalid() {
    testCommandExecution(false, 1, false, false)
  }

  @Test
  void should_execute_command_if_args_are_valid() {
    testCommandExecution(true, 0, true, false)
  }

  @Test
  void should_lock_if_command_requires_it() {
    testCommandExecution(true, 0, true, true)
  }

  @Test
  void should_wait_on_lock_to_clear_before_executing() {
    StorageServiceFactory.metaClass.'static'.create = { Properties p -> [:] as StorageService }

    def command = [:]
    command['getArgs'] = { -> [] }
    command['execute'] = { s,p -> 0 }
    command['validateArgs'] = { p -> }

    Command.metaClass.'static'.lookup = { String s -> }
    Command.metaClass.'static'.instantiate = { Class c -> command as Command }

    def agent = new SuperintendentAgent()
    agent.init(props)

    def waited = false
    def attempts = []

    File.metaClass.exists = { -> if (waited) return false; waited = true }

    agent.call()

    assert waited    
  }

  @Test
  void should_return_nonzero_code_when_lock_timeout_expires() {
    StorageServiceFactory.metaClass.'static'.create = { Properties p -> [:] as StorageService }

    def command = [:]
    command['getArgs'] = { -> [] }
    command['execute'] = { s,p -> 0 }
    command['validateArgs'] = { p -> }

    Command.metaClass.'static'.lookup = { String s -> }
    Command.metaClass.'static'.instantiate = { Class c -> command as Command }

    def agent = new SuperintendentAgent()
    agent.init(props)

    File.metaClass.exists = { -> throw new TimeoutException() }

    assert agent.call() == 2
  }

  private testCommandExecution(argsValid, expectedResult, commandExecuted, shouldLock = false) {
    StorageServiceFactory.metaClass.'static'.create = { Properties p -> [:] as StorageService }

    def agent = new SuperintendentAgent()
    agent.init(props)

    boolean locked = false
    boolean unlocked = false
    boolean executed = false

    agent.metaClass.lock = { -> locked = true }
    agent.metaClass.unlock = { -> unlocked = true }
    agent.metaClass.isLocked = { -> false }

    def command = [:]
    command['getArgs']    = { -> [] }
    command['execute'] = { s,p -> executed = true; 0 }
    command['validateArgs'] = { p -> argsValid ? null : "invalid" }
    command['shouldLock'] = { -> shouldLock }

    Command.metaClass.'static'.lookup = { String s -> }
    Command.metaClass.'static'.instantiate = { Class c -> command as Command }
  
    assert agent.call() == expectedResult
    assert executed == commandExecuted

    assert locked == shouldLock
    assert unlocked  == shouldLock
  }
}