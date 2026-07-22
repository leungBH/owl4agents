package org.owl4agents.overlay;

import java.util.List;
import java.util.Optional;

/**
 * v0.8.7 OV-003 / D10: Structured representation of a user's context
 * at capture time.
 *
 * <p>Each {@code UserContext} is converted by {@link StructuredStateConverter}
 * into OWL ABox axioms:</p>
 * <ul>
 *   <li>{@code ClassAssertion(userIri, User)}</li>
 *   <li>{@code ObjectPropertyAssertion(hasLocation, userIri, locationIri)}
 *       when {@code location} is present.</li>
 *   <li>One {@code ClassAssertion(userIri, PermissionClass)} per entry in
 *       {@code permissions} (each permission is the IRI of a permission
 *       class the user is asserted to belong to).</li>
 *   <li>One {@code DataPropertyAssertion(hasPreference, userIri, literal)}
 *       per entry in {@code preferences}.</li>
 * </ul>
 *
 * @param userIri      absolute IRI of the user individual
 * @param location     absolute IRI of the user's current location, or empty
 * @param preferences  list of preference strings (keyed free-form; each
 *                     becomes a {@code hasPreference} data property assertion)
 * @param permissions  list of absolute IRIs of permission classes the user
 *                     holds (each becomes a {@code ClassAssertion})
 */
public record UserContext(
    String userIri,
    Optional<String> location,
    List<String> preferences,
    List<String> permissions
) {
    public UserContext {
        if (userIri == null || userIri.isBlank()) {
            throw new IllegalArgumentException("userIri must not be blank");
        }
        if (location == null) location = Optional.empty();
        if (preferences == null) {
            preferences = List.of();
        } else {
            preferences = List.copyOf(preferences);
        }
        if (permissions == null) {
            permissions = List.of();
        } else {
            permissions = List.copyOf(permissions);
        }
    }

    /**
     * Convenience constructor for a user with location and permissions
     * but no preferences.
     */
    public UserContext(String userIri, String location, List<String> permissions) {
        this(userIri,
            Optional.ofNullable(location).filter(s -> !s.isBlank()),
            List.of(),
            permissions == null ? List.of() : List.copyOf(permissions));
    }
}
