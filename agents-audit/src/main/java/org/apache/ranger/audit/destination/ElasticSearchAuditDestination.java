/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ranger.audit.destination;

import java.io.File;
import java.security.PrivilegedActionException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.thirdparty.com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthSchemeProvider;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.AuthSchemes;
import org.apache.http.config.Lookup;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.impl.auth.SPNegoSchemeFactory;
import org.apache.ranger.audit.model.AuditEventBase;
import org.apache.ranger.audit.model.AuthzAuditEvent;
import org.apache.ranger.audit.provider.MiscUtil;
import org.apache.ranger.authorization.credutils.CredentialsProviderUtil;
import org.apache.ranger.authorization.credutils.kerberos.KerberosCredentialsProvider;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.Subject;
import javax.security.auth.kerberos.KerberosTicket;

public class ElasticSearchAuditDestination extends AuditDestination {
    private static final Logger LOG = LoggerFactory.getLogger(ElasticSearchAuditDestination.class);

    public static final String CONFIG_URLS = "urls";
    public static final String CONFIG_PORT = "port";
    public static final String CONFIG_USER = "user";
    public static final String CONFIG_PWRD = "password";
    public static final String CONFIG_PROTOCOL = "protocol";
    public static final String CONFIG_INDEX = "index";
    public static final String CONFIG_PREFIX = "ranger.audit.elasticsearch";
    public static final String DEFAULT_INDEX = "ranger_audits";

    private String index = CONFIG_INDEX;
    private final AtomicReference<ElasticsearchClient> clientRef = new AtomicReference<>(null);
    private String protocol;
    private String user;
    private int port;
    private String password;
    private String hosts;
    private Subject subject;

    public ElasticSearchAuditDestination() {
        propPrefix = CONFIG_PREFIX;
    }


    @Override
    public void init(Properties props, String propPrefix) {
        super.init(props, propPrefix);
        this.protocol = getStringProperty(props, propPrefix + "." + CONFIG_PROTOCOL, "http");
        this.user = getStringProperty(props, propPrefix + "." + CONFIG_USER, "");
        this.password = getStringProperty(props, propPrefix + "." + CONFIG_PWRD, "");
        this.port = MiscUtil.getIntProperty(props, propPrefix + "." + CONFIG_PORT, 9200);
        this.index = getStringProperty(props, propPrefix + "." + CONFIG_INDEX, DEFAULT_INDEX);
        this.hosts = getHosts();
        LOG.info("Connecting to ElasticSearch: " + connectionString());
        getClient(); // Initialize client
    }

    private String connectionString() {
        return String.format(Locale.ROOT, "User:%s, %s://%s:%s/%s", user, protocol, hosts, port, index);
    }

    @Override
    public void stop() {
        super.stop();
        logStatus();
    }

    @Override
    public boolean log(Collection<AuditEventBase> events) {
        boolean ret = false;
        try {
            logStatusIfRequired();
            addTotalCount(events.size());
    
            ElasticsearchClient client = getClient();  // Замена на новый клиент
            if (null == client) {
                // ElasticSearch is still not initialized. So need return error
                addDeferredCount(events.size());
                return ret;
            }
    
            ArrayList<AuditEventBase> eventList = new ArrayList<>(events);
            BulkRequest.Builder bulkRequestBuilder = new BulkRequest.Builder();  // Использование билдера для нового клиента
            try {
                for (int i = 0; i < eventList.size(); i++) {
                    AuthzAuditEvent authzEvent = (AuthzAuditEvent) eventList.get(i);
                    String id = authzEvent.getEventId();
                    Map<String, Object> doc = toDoc(authzEvent);
                    bulkRequestBuilder.operations(op -> op.index(idx -> idx.index(index).id(id).document(doc)));
                }
            } catch (Exception ex) {
                addFailedCount(eventList.size());
                logFailedEvent(eventList, ex);
            }
            
            BulkRequest bulkRequest = bulkRequestBuilder.build();  // Завершаем создание BulkRequest
            BulkResponse response = client.bulk(bulkRequest);  // Отправка запроса
    
            if (response.errors()) {
                addFailedCount(eventList.size());
                logFailedEvent(eventList, "Errors in Bulk Response");
            } else {
                int successCount = 0;
                int failedCount = 0;
                // Исправляем получение элементов ответа
                for (BulkResponseItem item : response.items()) {
                    AuditEventBase itemRequest = eventList.get(successCount + failedCount); // Используем счётчики для корректного доступа
                    if (item.error() != null) {
                        addFailedCount(1);
                        logFailedEvent(Arrays.asList(itemRequest), item.error().reason());
                        failedCount++;
                    } else {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug(String.format("Indexed %s", itemRequest.getEventKey()));
                        }
                        addSuccessCount(1);
                        ret = true;
                        successCount++;
                    }
                }
                LOG.info("Successfully sent messages to ElasticSearch: " + successCount + " ,Failed to send messages: " + failedCount);
            }
        } catch (Throwable t) {
            addDeferredCount(events.size());
            logError("Error sending message to ElasticSearch", t);
        }
        return ret;
    }

    /*
    * (non-Javadoc)
    *
    * @see org.apache.ranger.audit.provider.AuditProvider#flush()
    */
    @Override
    public void flush() {
        // Empty flush method
    }

    public boolean isAsync() {
        return true;
    }

    synchronized ElasticsearchClient getClient() {
        ElasticsearchClient client = clientRef.get();
        if (client == null) {
            synchronized (ElasticSearchAuditDestination.class) {
                client = clientRef.get();
                if (client == null) {
                    client = newClient();
                    clientRef.set(client);
                }
            }
        }
        if (subject != null) {
            KerberosTicket ticket = CredentialsProviderUtil.getTGT(subject);
            try {
                if (new Date().getTime() > ticket.getEndTime().getTime()) {
                    clientRef.set(null);
                    CredentialsProviderUtil.ticketExpireTime80 = 0;
                    client = newClient();
                    clientRef.set(client);
                } else if (CredentialsProviderUtil.ticketWillExpire(ticket)) {
                    subject = CredentialsProviderUtil.login(user, password);
                }
            } catch (PrivilegedActionException e) {
                LOG.error("PrivilegedActionException:", e);
                throw new RuntimeException(e);
            }
        }
        return client;
    }

    private final AtomicLong lastLoggedAt = new AtomicLong(0);

    public static RestClientBuilder getRestClientBuilder(String urls, String protocol, String user, String password, int port) {
        RestClientBuilder restClientBuilder = RestClient.builder(
                MiscUtil.toArray(urls, ",").stream()
                        .map(x -> new HttpHost(x, port, protocol))
                        .toArray(HttpHost[]::new)
        );
        ThreadFactory clientThreadFactory = new ThreadFactoryBuilder()
                .setNameFormat("ElasticSearch rest client %s")
                .setDaemon(true)
                .build();
        if (StringUtils.isNotBlank(user) && StringUtils.isNotBlank(password) && !user.equalsIgnoreCase("NONE") && !password.equalsIgnoreCase("NONE")) {
            if (password.contains("keytab") && new File(password).exists()) {
                final KerberosCredentialsProvider credentialsProvider =
                        CredentialsProviderUtil.getKerberosCredentials(user, password);
                Lookup<AuthSchemeProvider> authSchemeRegistry = RegistryBuilder.<AuthSchemeProvider>create()
                        .register(AuthSchemes.SPNEGO, new SPNegoSchemeFactory()).build();
                restClientBuilder.setHttpClientConfigCallback(clientBuilder -> {
                    clientBuilder.setThreadFactory(clientThreadFactory);
                    clientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                    clientBuilder.setDefaultAuthSchemeRegistry(authSchemeRegistry);
                    return clientBuilder;
                });
            } else {
                final CredentialsProvider credentialsProvider =
                        CredentialsProviderUtil.getBasicCredentials(user, password);
                restClientBuilder.setHttpClientConfigCallback(clientBuilder -> {
                    clientBuilder.setThreadFactory(clientThreadFactory);
                    clientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                    return clientBuilder;
                });
            }
        } else {
            LOG.error("ElasticSearch Credentials not provided!!");
            final CredentialsProvider credentialsProvider = null;
            restClientBuilder.setHttpClientConfigCallback(clientBuilder -> {
                clientBuilder.setThreadFactory(clientThreadFactory);
                clientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                return clientBuilder;
            });
        }
        return restClientBuilder;
    }

    private ElasticsearchClient newClient() {
        try {
            if (StringUtils.isNotBlank(user) && StringUtils.isNotBlank(password) && password.contains("keytab") && new File(password).exists()) {
                subject = CredentialsProviderUtil.login(user, password);
            }
            RestClientBuilder restClientBuilder =
                    getRestClientBuilder(hosts, protocol, user, password, port);

            RestClient restClient = restClientBuilder.build();
            RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
            ElasticsearchClient client = new ElasticsearchClient(transport);

            if (LOG.isDebugEnabled()) {
                LOG.debug("Initialized client");
            }

            boolean exists = false;
            try {
                exists = client.indices().exists(c -> c.index(this.index)).value();
            } catch (Exception e) {
                LOG.warn("Error validating index " + this.index);
            }
            if (exists) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Index exists");
                }
            } else {
                LOG.info("Index does not exist");
            }
            return client;
        } catch (Throwable t) {
            lastLoggedAt.updateAndGet(lastLoggedAt -> {
                long now = System.currentTimeMillis();
                long elapsed = now - lastLoggedAt;
                if (elapsed > TimeUnit.MINUTES.toMillis(1)) {
                    LOG.error("Can't connect to ElasticSearch server: " + connectionString(), t);
                    return now;
                } else {
                    return lastLoggedAt;
                }
            });
            return null;
        }
    }

    private String getHosts() {
        String urls = MiscUtil.getStringProperty(props, propPrefix + "." + CONFIG_URLS);
        if (urls != null) {
            urls = urls.trim();
        }
        if ("NONE".equalsIgnoreCase(urls)) {
            urls = null;
        }
        return urls;
    }

    private String getStringProperty(Properties props, String propName, String defaultValue) {
        String value = MiscUtil.getStringProperty(props, propName);
        if (null == value) {
            return defaultValue;
        }
        return value;
    }

    Map<String, Object> toDoc(AuthzAuditEvent auditEvent) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", auditEvent.getEventId());
        doc.put("access", auditEvent.getAccessType());
        doc.put("enforcer", auditEvent.getAclEnforcer());
        doc.put("agent", auditEvent.getAgentId());
        doc.put("repo", auditEvent.getRepositoryName());
        doc.put("sess", auditEvent.getSessionId());
        doc.put("reqUser", auditEvent.getUser());
        doc.put("reqData", auditEvent.getRequestData());
        doc.put("resource", auditEvent.getResourcePath());
        doc.put("cliIP", auditEvent.getClientIP());
        doc.put("logType", auditEvent.getLogType());
        doc.put("result", auditEvent.getAccessResult());
        doc.put("policy", auditEvent.getPolicyId());
        doc.put("repoType", auditEvent.getRepositoryType());
        doc.put("resType", auditEvent.getResourceType());
        doc.put("reason", auditEvent.getResultReason());
        doc.put("action", auditEvent.getAction());
        doc.put("evtTime", auditEvent.getEventTime());
        doc.put("seq_num", auditEvent.getSeqNum());
        doc.put("event_count", auditEvent.getEventCount());
        doc.put("event_dur_ms", auditEvent.getEventDurationMS());
        doc.put("tags", auditEvent.getTags());
        doc.put("cluster", auditEvent.getClusterName());
        doc.put("zoneName", auditEvent.getZoneName());
        doc.put("agentHost", auditEvent.getAgentHostname());
        doc.put("policyVersion", auditEvent.getPolicyVersion());
        return doc;
    }

} 