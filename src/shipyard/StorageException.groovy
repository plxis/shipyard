package shipyard

public class StorageException extends ShipyardException {
  int errorCode = -1

  public StorageException(String message, int code, Throwable throwable) {
    super(message, throwable)
    errorCode = code
  }

  public StorageException(String message, int code) {
    this(message, code, null)
  }

  public StorageException(String message) {
    this(message, -1, null)
  }
}