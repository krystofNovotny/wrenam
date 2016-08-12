/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2015-2016 ForgeRock AS.
 */

package org.forgerock.openam.uma;

import static org.forgerock.json.JsonValue.*;
import static org.forgerock.openam.i18n.apidescriptor.ApiDescriptorConstants.*;
import static org.forgerock.openam.uma.UmaConstants.BackendPolicy.*;
import static org.forgerock.openam.uma.UmaConstants.UMA_POLICY_SCHEME;
import static org.forgerock.openam.uma.UmaConstants.UmaPolicy.*;
import static org.forgerock.openam.uma.UmaPolicyUtils.getPolicyScopes;
import static org.forgerock.openam.uma.UmaPolicyUtils.getPolicySubject;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.forgerock.api.annotations.Description;
import org.forgerock.api.annotations.Title;
import org.forgerock.json.JsonValue;
import org.forgerock.json.JsonValueException;
import org.forgerock.json.resource.BadRequestException;
import org.forgerock.json.resource.ResourceResponse;
import org.forgerock.openam.oauth2.ResourceSetDescription;

/**
 * Represents an UMA policy with operations to convert to and from underlying backend policies and JSON format.
 *
 * @since 13.0.0
 */
@Title(UMA_POLICY_RESOURCE + "umaPolicyResource." + TITLE)
@Description(UMA_POLICY_RESOURCE + "umaPolicyResource." + DESCRIPTION)
public class UmaPolicy {

    /**
     * Parses the unique resource set id, that the UMA policy relates to, from the UMA policy JSON.
     *
     * @param policy The UMA policy in JSON format.
     * @return The UMA policy ID.
     */
    public static String idOf(JsonValue policy) throws BadRequestException {
        if (!policy.isDefined(POLICY_ID_KEY)) {
            throw new BadRequestException("Missing required field: " + POLICY_ID_KEY);
        }
        return policy.get(POLICY_ID_KEY).asString();
    }

    /**
     * Parses the UMA policy JSON into a {@code UmaPolicy} instance.
     *
     * @param resourceSet The resource set the policy relates to.
     * @param policy      The UMA policy in JSON format.
     * @return A {@code UmaPolicy} instance.
     * @throws BadRequestException If the UMA policy JSON is not valid.
     */
    public static UmaPolicy valueOf(ResourceSetDescription resourceSet, JsonValue policy) throws BadRequestException {
        validateUmaPolicy(policy);
        return new UmaPolicy(resourceSet, policy, null);
    }

    private static void validateUmaPolicy(JsonValue policy) throws BadRequestException {
        try {
            policy.get(POLICY_ID_KEY).required();
        } catch (JsonValueException e) {
            throw new BadRequestException("Invalid UMA policy. Missing required attribute, 'policyId'.");
        }
        try {
            policy.get(POLICY_ID_KEY).asString();
        } catch (JsonValueException e) {
            throw new BadRequestException("Invalid UMA policy. Required attribute, 'policyId', must be a "
                    + "String.");
        }

        try {
            policy.get(PERMISSIONS_KEY).required();
        } catch (JsonValueException e) {
            throw new BadRequestException("Invalid UMA policy. Missing required attribute, 'permissions'.");
        }
        try {
            policy.get(PERMISSIONS_KEY).asList();
        } catch (JsonValueException e) {
            throw new BadRequestException("Invalid UMA policy. Required attribute, 'permissions', must be an "
                    + "array.");
        }

        for (JsonValue permission : policy.get(PERMISSIONS_KEY)) {
            try {
                permission.get(SUBJECT_KEY).required();
            } catch (JsonValueException e) {
                throw new BadRequestException("Invalid UMA policy permission. Missing required attribute, 'subject'.");
            }
            try {
                permission.get(SUBJECT_KEY).asString();
            } catch (JsonValueException e) {
                throw new BadRequestException("Invalid UMA policy permission. Required attribute, 'subject', "
                        + "must be a String.");
            }

            try {
                permission.get(SCOPES_KEY).required();
            } catch (JsonValueException e) {
                throw new BadRequestException("Invalid UMA policy permission. Missing required attribute, 'scopes'.");
            }
            try {
                permission.get(SCOPES_KEY).asList(String.class);
            } catch (JsonValueException e) {
                throw new BadRequestException("Invalid UMA policy permission. Required attribute, 'scopes', "
                        + "must be an array of Strings.");
            }
        }
    }

    /**
     * Converts underlying backend policies into an {@code UmaPolicy}.
     *
     * @param resourceSet The resource set the policy relates to.
     * @param policies    The collection of underlying backend policies.
     * @return A {@code UmaPolicy} instance.
     * @throws BadRequestException If the underlying policies do not underpin a valid UMA policy.
     */
    public static UmaPolicy fromUnderlyingPolicies(ResourceSetDescription resourceSet, Collection<ResourceResponse> policies)
            throws BadRequestException {

        Set<String> underlyingPolicyIds = new HashSet<>();
        Map<String, Set<String>> subjectPermissions = new HashMap<>();
        for (ResourceResponse policy : policies) {
            underlyingPolicyIds.add(policy.getId());
            JsonValue policyContent = policy.getContent();
            String subject = getPolicySubject(policyContent);
            subjectPermissions.put(subject, getPolicyScopes(policyContent));
        }
        List<Object> permissions = array();
        JsonValue umaPolicy = json(object(
                field(POLICY_ID_KEY, resourceSet.getId()),
                field(POLICY_NAME, resourceSet.getName()),
                field(PERMISSIONS_KEY, permissions)));
        for (Map.Entry<String, Set<String>> permission : subjectPermissions.entrySet()) {
            permissions.add(object(
                    field(SUBJECT_KEY, permission.getKey()),
                    field(SCOPES_KEY, permission.getValue())));
        }
        return new UmaPolicy(resourceSet, umaPolicy, underlyingPolicyIds);
    }

    private final ResourceSetDescription resourceSet;
    @Title(UMA_POLICY_RESOURCE + "umaPolicyResource.policy." + TITLE)
    @Description(UMA_POLICY_RESOURCE + "umaPolicyResource.policy." + DESCRIPTION)
    private final JsonValue policy;
    private final Collection<String> underlyingPolicyIds;
    private Set<String> scopes;
    private Set<String> subjects;

    private UmaPolicy(ResourceSetDescription resourceSet, JsonValue policy, Collection<String> underlyingPolicyIds) {
        this.resourceSet = resourceSet;
        this.policy = policy;
        this.underlyingPolicyIds = underlyingPolicyIds;
    }

    /**
     * Gets the ID of this UMA policy which is the unique resource set ID that this policy relates to.
     *
     * @return The ID.
     */
    public String getId() {
        return policy.get(POLICY_ID_KEY).asString();
    }

    /**
     * Gets the revision of this UMA policy.
     *
     * @return The revision.
     */
    public String getRevision() {
        return Long.toString(hashCode());
    }

    /**
     * Converts the {@code UmaPolicy} to JSON.
     *
     * @return The JSON representation of the UMA policy.
     */
    public JsonValue asJson() {
        return policy;
    }

    public Collection<String> getUnderlyingPolicyIds() {
        return underlyingPolicyIds;
    }

    /**
     * Parses the unique set of scopes that are defined for all subject in this UMA policy.
     *
     * @return The set of defined scopes on the UMA policy.
     */
    public synchronized Set<String> getScopes() {
        if (scopes == null) {
            scopes = new HashSet<>();
            for (JsonValue permission : policy.get(PERMISSIONS_KEY)) {
                scopes.addAll(permission.get(SCOPES_KEY).asList(String.class));
            }
        }
        return scopes;
    }

    /**
     * Converts the {@code UmaPolicy} into its underlying backend policies in JSON format.
     *
     * @return The set of underlying backend policies that represent this UMA policy.
     */
    public Set<JsonValue> asUnderlyingPolicies(String policyOwnerName) {
        Set<JsonValue> underlyingPolicies = new HashSet<>();
        for (JsonValue p : policy.get(PERMISSIONS_KEY)) {
            underlyingPolicies.add(createPolicyJson(policyOwnerName, p));
        }
        return underlyingPolicies;
    }

    /**
     * Receives policy in the structure:
     * <pre>
     * {
     *    "scopes" : [ "scope1", ... ],
     *    "subject" : "theSubject"
     * }
     * </pre>
     */
    private JsonValue createPolicyJson(String policyOwnerName, JsonValue permission) {
        String subject = permission.get(SUBJECT_KEY).asString();
        String policyName = resourceSet.getName() + " - " + policyOwnerName + " - " + resourceSet.getId()
                + "-" + subject.hashCode();

        Map<String, Object> actions = new HashMap<>();
        for (String scope : permission.get(SCOPES_KEY).asCollection(String.class)) {
            actions.put(scope, true);
        }
        return json(object(
                field(BACKEND_POLICY_NAME_KEY, policyName),
                field("applicationName", getResourceServerId().toLowerCase()), //Lowercase as ldap is case insensitive
                field(BACKEND_POLICY_RESOURCE_TYPE_KEY, resourceSet.getId()),
                field(BACKEND_POLICY_RESOURCES_KEY, array(UMA_POLICY_SCHEME + getId())),
                field(BACKEND_POLICY_ACTION_VALUES_KEY, actions),
                field(BACKEND_POLICY_SUBJECT_KEY, object(
                        field(BACKEND_POLICY_SUBJECT_TYPE_KEY, BACKEND_POLICY_SUBJECT_TYPE_JWT_CLAIM),
                        field(BACKEND_POLICY_SUBJECT_CLAIM_NAME_KEY, BACKEND_POLICY_SUBJECT_CLAIM_NAME),
                        field(BACKEND_POLICY_SUBJECT_CLAIM_VALUE_KEY, subject))
                )
        ));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UmaPolicy)) {
            return false;
        }
        UmaPolicy policy1 = (UmaPolicy) o;
        return policy.asMap().equals(policy1.policy.asMap())
                && resourceSet.equals(policy1.resourceSet);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        int result = resourceSet.hashCode();
        result = 31 * result + policy.hashCode();
        return result;
    }

    /**
     * Parses the unique set of subjects that have permissions defined in this UMA policy.
     *
     * @return The set of defined subjects on the UMA policy.
     */
    public synchronized Set<String> getSubjects() {
        if (subjects == null) {
            subjects = convertSubjectsFromUmaPolicy();
        }
        return subjects;
    }

    private Set<String> convertSubjectsFromUmaPolicy() {
        Set<String> subjects = new HashSet<String>();
        for (JsonValue permission : policy.get(PERMISSIONS_KEY)) {
            subjects.add(permission.get(SUBJECT_KEY).asString());
        }
        return subjects;
    }

    /**
     * Gets the Resource Server Id that the resource set was registered by.
     *
     * @return The Resource Server Id.
     */
    public String getResourceServerId() {
        return resourceSet.getClientId();
    }

    public ResourceSetDescription getResourceSet() {
        return resourceSet;
    }
}
