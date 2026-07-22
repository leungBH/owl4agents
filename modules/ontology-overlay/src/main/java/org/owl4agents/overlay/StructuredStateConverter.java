package org.owl4agents.overlay;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLIndividual;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * v0.8.7 OV-003 / D10: Converts a structured {@link EnvironmentSnapshot}
 * (Java objects) into a {@link Collection} of {@link OWLAxiom}s.
 *
 * <p>The converter uses a canonical dynamic-state vocabulary rooted at
 * {@link #DYNAMIC_NS}. The same IRIs are used when serializing an
 * EnvironmentSnapshot to RDF (Turtle/JSON-LD/N-Triples) so that the
 * RDF-input path ({@link RdfStateParser}) and the structured-object
 * path produce equivalent axiom sets (set equality ignoring blank node
 * IDs) per OV-003 "Java object and RDF equivalence".</p>
 *
 * <p>Vocabulary (all under {@code https://owl4agents.org/ontology/dynamic#}):</p>
 * <ul>
 *   <li>{@code User} — class asserted on each {@link UserContext}</li>
 *   <li>{@code ToolCall} — class asserted on each {@link ToolCallCandidate}</li>
 *   <li>{@code hasLocation} — object property linking a device/user to a
 *       location individual</li>
 *   <li>{@code hasState} — data property carrying a device state string</li>
 *   <li>{@code hasPreference} — data property carrying a user preference string</li>
 *   <li>{@code hasToolName} — data property carrying a tool name string</li>
 *   <li>{@code hasTargetEntity} — data property carrying a target entity IRI</li>
 *   <li>{@code hasArgument} — data property carrying a JSON-encoded argument</li>
 *   <li>{@code requestedBy} — object property linking a tool call to a user</li>
 * </ul>
 *
 * <p>Each device's {@code properties} map is converted to one
 * {@code DataPropertyAssertion} per entry, using the map key as the
 * property IRI and the value as an {@code xsd:string} literal.</p>
 */
public final class StructuredStateConverter {

    /** Canonical namespace for the dynamic-state vocabulary. */
    public static final String DYNAMIC_NS = "https://owl4agents.org/ontology/dynamic#";

    /** Class IRIs. */
    public static final IRI USER_CLASS_IRI = IRI.create(DYNAMIC_NS + "User");
    public static final IRI TOOL_CALL_CLASS_IRI = IRI.create(DYNAMIC_NS + "ToolCall");

    /** Object property IRIs. */
    public static final IRI HAS_LOCATION_IRI = IRI.create(DYNAMIC_NS + "hasLocation");
    public static final IRI REQUESTED_BY_IRI = IRI.create(DYNAMIC_NS + "requestedBy");

    /** Data property IRIs. */
    public static final IRI HAS_STATE_IRI = IRI.create(DYNAMIC_NS + "hasState");
    public static final IRI HAS_PREFERENCE_IRI = IRI.create(DYNAMIC_NS + "hasPreference");
    public static final IRI HAS_TOOL_NAME_IRI = IRI.create(DYNAMIC_NS + "hasToolName");
    public static final IRI HAS_TARGET_ENTITY_IRI = IRI.create(DYNAMIC_NS + "hasTargetEntity");
    public static final IRI HAS_ARGUMENT_IRI = IRI.create(DYNAMIC_NS + "hasArgument");

    private StructuredStateConverter() {
        // utility class
    }

    /**
     * Convert a full {@link EnvironmentSnapshot} to a collection of axioms.
     *
     * @param snapshot the snapshot (must not be null)
     * @return the axiom collection (never null, may be empty)
     */
    public static Collection<OWLAxiom> convert(EnvironmentSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        OWLDataFactory df = OWLManager.createOWLOntologyManager().getOWLDataFactory();
        List<OWLAxiom> axioms = new ArrayList<>();
        for (DeviceSnapshot device : snapshot.devices()) {
            axioms.addAll(convertDevice(device, df));
        }
        for (UserContext user : snapshot.userContexts()) {
            axioms.addAll(convertUser(user, df));
        }
        for (ToolCallCandidate call : snapshot.pendingToolCalls()) {
            axioms.addAll(convertToolCall(call, df));
        }
        return axioms;
    }

    /**
     * Convert a single {@link DeviceSnapshot} to axioms.
     */
    public static Collection<OWLAxiom> convertDevice(DeviceSnapshot device) {
        return convertDevice(device, OWLManager.createOWLOntologyManager().getOWLDataFactory());
    }

    /**
     * Convert a single {@link UserContext} to axioms.
     */
    public static Collection<OWLAxiom> convertUser(UserContext user) {
        return convertUser(user, OWLManager.createOWLOntologyManager().getOWLDataFactory());
    }

    /**
     * Convert a single {@link ToolCallCandidate} to axioms.
     */
    public static Collection<OWLAxiom> convertToolCall(ToolCallCandidate call) {
        return convertToolCall(call, OWLManager.createOWLOntologyManager().getOWLDataFactory());
    }

    // ── Internal conversion helpers ──

    private static Collection<OWLAxiom> convertDevice(DeviceSnapshot device, OWLDataFactory df) {
        List<OWLAxiom> axioms = new ArrayList<>();
        OWLNamedIndividual deviceInd = df.getOWLNamedIndividual(IRI.create(device.deviceIri()));
        // ClassAssertion(device, deviceType)
        OWLClass deviceClass = df.getOWLClass(IRI.create(device.deviceType()));
        axioms.add(df.getOWLClassAssertionAxiom(deviceClass, deviceInd));
        // ObjectPropertyAssertion(hasLocation, device, location)
        device.location().ifPresent(loc -> {
            OWLObjectProperty hasLocation = df.getOWLObjectProperty(HAS_LOCATION_IRI);
            OWLNamedIndividual locInd = df.getOWLNamedIndividual(IRI.create(loc));
            OWLObjectPropertyAssertionAxiom ax = df.getOWLObjectPropertyAssertionAxiom(
                hasLocation, deviceInd, locInd);
            axioms.add(ax);
        });
        // DataPropertyAssertion(hasState, device, state)
        device.state().ifPresent(state -> {
            OWLDataProperty hasState = df.getOWLDataProperty(HAS_STATE_IRI);
            OWLLiteral lit = df.getOWLLiteral(state);
            OWLDataPropertyAssertionAxiom ax = df.getOWLDataPropertyAssertionAxiom(
                hasState, deviceInd, lit);
            axioms.add(ax);
        });
        // One DataPropertyAssertion per entry in properties.
        device.properties().forEach((propIri, value) -> {
            OWLDataProperty prop = df.getOWLDataProperty(IRI.create(propIri));
            OWLLiteral lit = df.getOWLLiteral(value);
            axioms.add(df.getOWLDataPropertyAssertionAxiom(prop, deviceInd, lit));
        });
        return axioms;
    }

    private static Collection<OWLAxiom> convertUser(UserContext user, OWLDataFactory df) {
        List<OWLAxiom> axioms = new ArrayList<>();
        OWLNamedIndividual userInd = df.getOWLNamedIndividual(IRI.create(user.userIri()));
        // ClassAssertion(user, User)
        OWLClass userClass = df.getOWLClass(USER_CLASS_IRI);
        axioms.add(df.getOWLClassAssertionAxiom(userClass, userInd));
        // ObjectPropertyAssertion(hasLocation, user, location)
        user.location().ifPresent(loc -> {
            OWLObjectProperty hasLocation = df.getOWLObjectProperty(HAS_LOCATION_IRI);
            OWLNamedIndividual locInd = df.getOWLNamedIndividual(IRI.create(loc));
            axioms.add(df.getOWLObjectPropertyAssertionAxiom(hasLocation, userInd, locInd));
        });
        // DataPropertyAssertion(hasPreference, user, preference)
        OWLDataProperty hasPreference = df.getOWLDataProperty(HAS_PREFERENCE_IRI);
        for (String pref : user.preferences()) {
            OWLLiteral lit = df.getOWLLiteral(pref);
            axioms.add(df.getOWLDataPropertyAssertionAxiom(hasPreference, userInd, lit));
        }
        // ClassAssertion(user, PermissionClass) for each permission IRI.
        for (String permIri : user.permissions()) {
            OWLClass permClass = df.getOWLClass(IRI.create(permIri));
            axioms.add(df.getOWLClassAssertionAxiom(permClass, userInd));
        }
        return axioms;
    }

    private static Collection<OWLAxiom> convertToolCall(ToolCallCandidate call, OWLDataFactory df) {
        List<OWLAxiom> axioms = new ArrayList<>();
        OWLNamedIndividual callInd = df.getOWLNamedIndividual(callIndividualIri(call.callId()));
        // ClassAssertion(call, ToolCall)
        OWLClass toolCallClass = df.getOWLClass(TOOL_CALL_CLASS_IRI);
        axioms.add(df.getOWLClassAssertionAxiom(toolCallClass, callInd));
        // DataPropertyAssertion(hasToolName, call, toolName)
        OWLDataProperty hasToolName = df.getOWLDataProperty(HAS_TOOL_NAME_IRI);
        axioms.add(df.getOWLDataPropertyAssertionAxiom(
            hasToolName, callInd, df.getOWLLiteral(call.toolName())));
        // DataPropertyAssertion(hasTargetEntity, call, targetEntity)
        call.targetEntity().ifPresent(target -> {
            OWLDataProperty hasTarget = df.getOWLDataProperty(HAS_TARGET_ENTITY_IRI);
            axioms.add(df.getOWLDataPropertyAssertionAxiom(
                hasTarget, callInd, df.getOWLLiteral(target)));
        });
        // ObjectPropertyAssertion(requestedBy, call, user)
        call.requestedBy().ifPresent(user -> {
            OWLObjectProperty requestedBy = df.getOWLObjectProperty(REQUESTED_BY_IRI);
            OWLIndividual userInd = df.getOWLNamedIndividual(IRI.create(user));
            axioms.add(df.getOWLObjectPropertyAssertionAxiom(requestedBy, callInd, userInd));
        });
        // One DataPropertyAssertion(hasArgument, call, value) per argument.
        OWLDataProperty hasArgument = df.getOWLDataProperty(HAS_ARGUMENT_IRI);
        call.arguments().forEach((name, value) -> {
            OWLLiteral lit = df.getOWLLiteral(name + "=" + value);
            axioms.add(df.getOWLDataPropertyAssertionAxiom(hasArgument, callInd, lit));
        });
        return axioms;
    }

    /**
     * Derive the IRI of a tool call individual from its callId.
     */
    public static IRI callIndividualIri(String callId) {
        return IRI.create(DYNAMIC_NS + "call/" + callId);
    }
}
