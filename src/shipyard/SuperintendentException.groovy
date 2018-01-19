package shipyard

public class SuperintendentException extends Exception {
  public SuperintendentException(String message, Throwable throwable) {
    super(message, throwable)
  }

  public SuperintendentException(String message) {
    super(message)
  }
}