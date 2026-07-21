package org.owl4agents.core;

import java.util.Map;

import org.owl4agents.core.model.ReasonerCallMetadata;

/**
 * Shared result wrapper for all service operations.
 * Success results contain data and metadata.
 * Error results contain structured error information.
 *
 * <p>v0.8.6: Both {@link Success} and {@link Error} gain a nullable
 * {@link ReasonerCallMetadata} field, populated by {@code ReasonerCallWrapper}
 * on every reasoner call. Existing factory methods are retained and produce
 * {@code reasonerMetadata = null} (additive field, no migration needed).</p>
 */
public sealed interface ServiceResult<T> permits ServiceResult.Success, ServiceResult.Error {

    boolean isSuccess();

    record Success<T>(T data, ResultMetadata metadata, ReasonerCallMetadata reasonerMetadata) implements ServiceResult<T> {
        @Override
        public boolean isSuccess() {
            return true;
        }

        public Success(T data, ResultMetadata metadata) {
            this(data, metadata, null);
        }
    }

    record Error<T>(ServiceError error, ReasonerCallMetadata reasonerMetadata) implements ServiceResult<T> {
        @Override
        public boolean isSuccess() {
            return false;
        }

        public Error(ServiceError error) {
            this(error, null);
        }
    }

    static <T> ServiceResult<T> success(T data, ResultMetadata metadata) {
        return new Success<>(data, metadata);
    }

    static <T> ServiceResult<T> success(T data, ResultMetadata metadata, ReasonerCallMetadata reasonerMetadata) {
        return new Success<>(data, metadata, reasonerMetadata);
    }

    static <T> ServiceResult<T> error(ServiceError error) {
        return new Error<>(error);
    }

    static <T> ServiceResult<T> error(ServiceError error, ReasonerCallMetadata reasonerMetadata) {
        return new Error<>(error, reasonerMetadata);
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
