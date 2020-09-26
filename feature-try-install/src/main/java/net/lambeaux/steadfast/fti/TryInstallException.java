package net.lambeaux.steadfast.fti;

public class TryInstallException extends RuntimeException {
  public TryInstallException() {}

  public TryInstallException(String message) {
    super(message);
  }

  public TryInstallException(String message, Throwable cause) {
    super(message, cause);
  }

  public TryInstallException(Throwable cause) {
    super(cause);
  }

  public TryInstallException(
      String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
    super(message, cause, enableSuppression, writableStackTrace);
  }
}
