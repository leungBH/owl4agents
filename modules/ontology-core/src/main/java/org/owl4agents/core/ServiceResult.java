package org.owl4agents.core;

import java.util.Map;

/**
 * Shared result wrapper for all service operations.
 * Success results contain data and metadata.
 * Error results contain structured error information.
 */
public sealed interface ServiceResult<T> permits ServiceResult.Success, ServiceResult.Error {

    boolean isSuccess();

    record Success<T>(T data, ResultMetadata metadata) implements ServiceResult<T> {
        @Override
        public boolean isSuccess() {
            return true;
        }
    }

    record Error<T>(ServiceError error) implements ServiceResult<T> {
        @Override
        public boolean isSuccess() {
            return false;
        }
    }

    static <T> ServiceResult<T> success(T data, ResultMetadata metadata) {
        return new Success<>(data, metadata);
    }

    static <T> ServiceResult<T> error(ServiceError error) {
        return new Error<>(error);
    }

    static <T> ServiceResult<T> error(ErrorCode code) {
        return new Error<>(ServiceError.of(code));
    }

    static <T> ServiceResult<T> error(ErrorCode code, String message) {
        return new Error<>(ServiceError.of(code, message));
    }

    static <T> ServiceResult<T> error(ErrorCode code, String message, Map<String, Object> details) {
        return new Error<>(ServiceError.of(code, message, details));
    }
}