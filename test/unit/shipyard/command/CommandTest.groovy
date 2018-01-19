package shipyard.command

import org.junit.*

public class CommandTest {
  @Test
  public void should_list_all_commands() {
    assert Command.list().unique().size() == 7
  }

  @Test
  public void should_lookup() {
    assert Command.lookup("Command") == Command
  }

  @Test (expected=ClassNotFoundException)
  public void should_fail_to_lookup() {
    Command.lookup("fake")
  }

  @Test
  public void should_instantiate() {
    assert Command.instantiate(java.lang.String) instanceof String
  }
}