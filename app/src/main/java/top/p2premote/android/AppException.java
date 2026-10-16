package top.p2premote.android;

/** The business code is stable; message and cause only add context. */
final class AppException extends RuntimeException {
    final String errorCode;

    AppException(String code, String message) { this(code, message, null); }

    AppException(String code, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = ErrorCodes.valid(code) ? code : ErrorCodes.NativeDecodeInvalidPayload;
    }

    static AppException from(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 64; depth++, current = current.getCause()) {
            if (current instanceof AppException) return (AppException) current;
            if (current instanceof ApiClient.ApiException) {
                ApiClient.ApiException api = (ApiClient.ApiException) current;
                return new AppException(api.errorCode, api.getMessage(), api);
            }
        }
        return new AppException(ErrorCodes.RuntimeExecutionUnclassified,
                error.getMessage() == null ? "建立连接失败" : error.getMessage(), error);
    }
}
