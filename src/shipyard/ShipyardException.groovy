package shipyard

public class ShipyardException extends Exception {
  public ShipyardException(String message, Throwable throwable) {
    super(message, throwable)
  }

  public ShipyardException(String message) {
    super(message)
  }
}