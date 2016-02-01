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
 * Copyright 2016 ForgeRock AS.
 */

package org.forgerock.openam.core.rest.sms;

import static org.forgerock.json.JsonValue.*;
import static org.forgerock.json.resource.Responses.*;
import static org.forgerock.util.promise.Promises.*;

import com.iplanet.sso.SSOException;
import com.iplanet.sso.SSOToken;
import com.sun.identity.idm.AMIdentity;
import com.sun.identity.idm.AMIdentityRepository;
import com.sun.identity.idm.IdRepoException;
import com.sun.identity.shared.debug.Debug;
import com.sun.identity.sm.OrganizationConfigManager;
import com.sun.identity.sm.SMSException;
import com.sun.identity.sm.ServiceManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Named;
import org.forgerock.json.JsonValue;
import org.forgerock.json.resource.ActionRequest;
import org.forgerock.json.resource.ActionResponse;
import org.forgerock.json.resource.CreateRequest;
import org.forgerock.json.resource.DeleteRequest;
import org.forgerock.json.resource.InternalServerErrorException;
import org.forgerock.json.resource.NotSupportedException;
import org.forgerock.json.resource.PatchRequest;
import org.forgerock.json.resource.QueryRequest;
import org.forgerock.json.resource.QueryResourceHandler;
import org.forgerock.json.resource.QueryResponse;
import org.forgerock.json.resource.ReadRequest;
import org.forgerock.json.resource.RequestHandler;
import org.forgerock.json.resource.ResourceException;
import org.forgerock.json.resource.ResourceResponse;
import org.forgerock.json.resource.UpdateRequest;
import org.forgerock.openam.rest.RealmContext;
import org.forgerock.openam.rest.RestUtils;
import org.forgerock.openam.rest.query.QueryResponsePresentation;
import org.forgerock.openam.rest.resource.SSOTokenContext;
import org.forgerock.services.context.Context;
import org.forgerock.util.promise.Promise;

/**
 * Endpoint to handle requests for information about specific services available
 * to be generated in OpenAM. Requesting 'getAllTypes' will return the set of all
 * service types on the server, regardless of those currently instantiated. Requesting
 * 'getCreatableTypes' will return the set of all service types which have not been
 * instantiated on the server.
 */
public class ServiceInstanceCollectionHandler implements RequestHandler {

    private final static String NAME = "name";
    private final static String RESULT = "result";

    private final static String getAllTypesAction = "getAllTypes";
    private final static String getCreatableTypesAction = "getCreatableTypes";

    private final Debug debug;
    private final SmsConsoleServiceNameFilter consoleNameFilter;

    @Inject
    ServiceInstanceCollectionHandler(@Named("frRest") Debug debug, SmsConsoleServiceNameFilter consoleNameFilter) {
        this.debug = debug;
        this.consoleNameFilter = consoleNameFilter;
    }

    @Override
    public Promise<QueryResponse, ResourceException> handleQuery(Context context, QueryRequest queryRequest,
                                                                 QueryResourceHandler queryResourceHandler) {
        String searchForId;
        try {
            searchForId = queryRequest.getQueryFilter().accept(new IdQueryFilterVisitor(), null);
        } catch (UnsupportedOperationException e) {
            return new NotSupportedException("Query not supported: " + queryRequest.getQueryFilter()).asPromise();
        }
        if (queryRequest.getPagedResultsCookie() != null || queryRequest.getPagedResultsOffset() > 0 ||
                queryRequest.getPageSize() > 0) {
            return new NotSupportedException("Query paging not currently supported").asPromise();
        }

        final SSOToken ssoToken = context.asContext(SSOTokenContext.class).getCallerSSOToken();
        final String realm = context.asContext(RealmContext.class).getResolvedRealm();
        final List<ResourceResponse> resourceResponses = new ArrayList<>();

        try {
            final ServiceManager sm = getServiceManager(ssoToken);
            final Set<String> services = sm.getOrganizationConfigManager(realm).getAssignedServices();
            services.addAll(getIdentityServices(realm, ssoToken).getAssignedServices());
            consoleNameFilter.filter(services);

            final Map<String, String> serviceNameMap = consoleNameFilter.mapNameToDisplayName(services);

            for (String instanceName : serviceNameMap.keySet()) {

                if (searchForId == null || searchForId.equals(serviceNameMap.get(instanceName))) {

                    final JsonValue result = json(object(
                            field(ResourceResponse.FIELD_CONTENT_ID, instanceName),
                            field(NAME, serviceNameMap.get(instanceName))));

                    resourceResponses.add(newResourceResponse(instanceName, String.valueOf(result.hashCode()), result));
                }
            }

        } catch (SSOException | SMSException | IdRepoException e) {
            debug.error("ServiceInstanceCollectionHandler:: Unable to query SMS config: ", e);
            return new InternalServerErrorException("Unable to query SMS config.", e).asPromise();
        }

        return QueryResponsePresentation.perform(queryResourceHandler, queryRequest, resourceResponses);
    }

    @Override
    public Promise<ActionResponse, ResourceException> handleAction(Context context, ActionRequest actionRequest) {
        switch (actionRequest.getAction()) {

            case getAllTypesAction :
                try {
                    return getAllTypesAction(context);
                } catch (SMSException | SSOException | IdRepoException e) {
                    debug.error("ServiceInstanceCollectionHandler::getAllTypes - Unable to query SMS config", e);
                    return new InternalServerErrorException("Unable to query SMS config: "
                            + e.getMessage(), e).asPromise();
                }
            case getCreatableTypesAction :
                try {
                    return getCreatableTypesAction(context);
                } catch (SMSException | SSOException | IdRepoException e) {
                    debug.error("ServiceInstanceCollectionHandler::getCreatableTypes - Unable to query SMS config", e);
                    return new InternalServerErrorException("Unable to query SMS config: "
                            + e.getMessage(), e).asPromise();
                }
            default :
                return RestUtils.generateUnsupportedOperation();
        }
    }

    private Promise<ActionResponse, ResourceException> getCreatableTypesAction(Context context)
            throws SMSException, SSOException, IdRepoException {

        final SSOToken ssoToken = context.asContext(SSOTokenContext.class).getCallerSSOToken();
        final String realm = context.asContext(RealmContext.class).getResolvedRealm();
        final ServiceManager sm = getServiceManager(ssoToken);
        final List<Object> jsonOutput = array();

        final Set<String> services = sm.getOrganizationConfigManager(realm).getAssignableServices();
        services.addAll(getIdentityServices(realm, ssoToken).getAssignableServices());
        consoleNameFilter.filter(services);

        final Map<String, String> serviceNameMap = consoleNameFilter.mapNameToDisplayName(services);

        for (Map.Entry<String, String> entry : serviceNameMap.entrySet()) {
            jsonOutput.add(object(
                    field(ResourceResponse.FIELD_CONTENT_ID, entry.getKey()),
                    field(NAME, entry.getValue())));
        }

        return newResultPromise(newActionResponse(json(object(field(RESULT, jsonOutput)))));
    }

    private Promise<ActionResponse, ResourceException> getAllTypesAction(Context context)
            throws SMSException, SSOException, IdRepoException {

        final SSOToken ssoToken = context.asContext(SSOTokenContext.class).getCallerSSOToken();
        final String realm = context.asContext(RealmContext.class).getResolvedRealm();
        final ServiceManager sm = getServiceManager(ssoToken);
        final OrganizationConfigManager ocm = sm.getOrganizationConfigManager(realm);
        final List<Object> jsonArray = array();

        final Set<String> services = ocm.getAssignableServices();
        services.addAll(ocm.getAssignedServices());
        services.addAll(getIdentityServices(realm, ssoToken).getAssignableServices());
        services.addAll(getIdentityServices(realm, ssoToken).getAssignedServices());
        consoleNameFilter.filter(services);

        final Map<String, String> serviceNameMap = consoleNameFilter.mapNameToDisplayName(services);

        for (String instanceName : serviceNameMap.keySet()) {
            jsonArray.add(object(
                    field(ResourceResponse.FIELD_CONTENT_ID, instanceName),
                    field(NAME, serviceNameMap.get(instanceName))));
        }

        return newResultPromise(newActionResponse(json(object(field(RESULT, jsonArray)))));
    }

    ServiceManager getServiceManager(SSOToken token) throws SMSException, SSOException {
        return new ServiceManager(token);
    }

    AMIdentity getIdentityServices(String realmName, SSOToken userSSOToken)
            throws IdRepoException, SSOException {
        AMIdentityRepository repo = new AMIdentityRepository(realmName, userSSOToken);
        return repo.getRealmIdentity();
    }

    @Override
    public Promise<ResourceResponse, ResourceException> handleCreate(Context context, CreateRequest createRequest) {
        return RestUtils.generateUnsupportedOperation();
    }

    @Override
    public Promise<ResourceResponse, ResourceException> handleDelete(Context context, DeleteRequest deleteRequest) {
        return RestUtils.generateUnsupportedOperation();
    }

    @Override
    public Promise<ResourceResponse, ResourceException> handlePatch(Context context, PatchRequest patchRequest) {
        return RestUtils.generateUnsupportedOperation();
    }

    @Override
    public Promise<ResourceResponse, ResourceException> handleRead(Context context, ReadRequest readRequest) {
        return RestUtils.generateUnsupportedOperation();
    }

    @Override
    public Promise<ResourceResponse, ResourceException> handleUpdate(Context context, UpdateRequest updateRequest) {
        return RestUtils.generateUnsupportedOperation();
    }

}
