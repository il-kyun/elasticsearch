/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.script;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

import org.apache.lucene.util.IOUtils;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.cluster.storedscripts.DeleteStoredScriptRequest;
import org.elasticsearch.action.admin.cluster.storedscripts.DeleteStoredScriptResponse;
import org.elasticsearch.action.admin.cluster.storedscripts.GetStoredScriptRequest;
import org.elasticsearch.action.admin.cluster.storedscripts.PutStoredScriptRequest;
import org.elasticsearch.action.admin.cluster.storedscripts.PutStoredScriptResponse;
import org.elasticsearch.cluster.AckedClusterStateUpdateTask;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateListener;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.breaker.CircuitBreakingException;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.cache.Cache;
import org.elasticsearch.common.cache.CacheBuilder;
import org.elasticsearch.common.cache.RemovalListener;
import org.elasticsearch.common.cache.RemovalNotification;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.search.lookup.SearchLookup;
import org.elasticsearch.template.CompiledTemplate;

public class ScriptService extends AbstractComponent implements Closeable, ClusterStateListener {

    static final String DISABLE_DYNAMIC_SCRIPTING_SETTING = "script.disable_dynamic";

    public static final Setting<Integer> SCRIPT_CACHE_SIZE_SETTING =
        Setting.intSetting("script.cache.max_size", 100, 0, Property.NodeScope);
    public static final Setting<TimeValue> SCRIPT_CACHE_EXPIRE_SETTING =
        Setting.positiveTimeSetting("script.cache.expire", TimeValue.timeValueMillis(0), Property.NodeScope);
    public static final Setting<Integer> SCRIPT_MAX_SIZE_IN_BYTES =
        Setting.intSetting("script.max_size_in_bytes", 65535, Property.NodeScope);
    public static final Setting<Integer> SCRIPT_MAX_COMPILATIONS_PER_MINUTE =
        Setting.intSetting("script.max_compilations_per_minute", 15, 0, Property.Dynamic, Property.NodeScope);

    private final Map<String, ScriptEngine> engines;

    private final Cache<CacheKey, CompiledScript> cache;

    private final ScriptModes scriptModes;
    private final ScriptContextRegistry scriptContextRegistry;

    private final ScriptMetrics scriptMetrics = new ScriptMetrics();

    private ClusterState clusterState;

    private int totalCompilesPerMinute;
    private long lastInlineCompileTime;
    private double scriptsPerMinCounter;
    private double compilesAllowedPerNano;

    public ScriptService(Settings settings, ScriptEngineRegistry scriptEngineRegistry,
                         ScriptContextRegistry scriptContextRegistry, ScriptSettings scriptSettings) throws IOException {
        super(settings);
        Objects.requireNonNull(scriptEngineRegistry);
        Objects.requireNonNull(scriptContextRegistry);
        Objects.requireNonNull(scriptSettings);
        if (Strings.hasLength(settings.get(DISABLE_DYNAMIC_SCRIPTING_SETTING))) {
            throw new IllegalArgumentException(DISABLE_DYNAMIC_SCRIPTING_SETTING + " is not a supported setting, replace with fine-grained script settings. \n" +
                    "Dynamic scripts can be enabled for all languages and all operations by replacing `script.disable_dynamic: false` with `script.inline: true` and `script.stored: true` in elasticsearch.yml");
        }

        this.scriptContextRegistry = scriptContextRegistry;
        int cacheMaxSize = SCRIPT_CACHE_SIZE_SETTING.get(settings);

        CacheBuilder<CacheKey, CompiledScript> cacheBuilder = CacheBuilder.builder();
        if (cacheMaxSize >= 0) {
            cacheBuilder.setMaximumWeight(cacheMaxSize);
        }

        TimeValue cacheExpire = SCRIPT_CACHE_EXPIRE_SETTING.get(settings);
        if (cacheExpire.getNanos() != 0) {
            cacheBuilder.setExpireAfterAccess(cacheExpire);
        }

        logger.debug("using script cache with max_size [{}], expire [{}]", cacheMaxSize, cacheExpire);
        this.cache = cacheBuilder.removalListener(new ScriptCacheRemovalListener()).build();
        this.engines = scriptEngineRegistry.getRegisteredLanguages();
        this.scriptModes = new ScriptModes(scriptContextRegistry, scriptSettings, settings);
        this.lastInlineCompileTime = System.nanoTime();
        this.setMaxCompilationsPerMinute(SCRIPT_MAX_COMPILATIONS_PER_MINUTE.get(settings));
    }

    void registerClusterSettingsListeners(ClusterSettings clusterSettings) {
        clusterSettings.addSettingsUpdateConsumer(SCRIPT_MAX_COMPILATIONS_PER_MINUTE, this::setMaxCompilationsPerMinute);
    }

    @Override
    public void close() throws IOException {
        IOUtils.close(engines.values());
    }

    private ScriptEngine getEngine(String lang) {
        ScriptEngine scriptEngine = engines.get(lang);
        if (scriptEngine == null) {
            throw new IllegalArgumentException("script_lang not supported [" + lang + "]");
        }
        return scriptEngine;
    }

    void setMaxCompilationsPerMinute(Integer newMaxPerMinute) {
        this.totalCompilesPerMinute = newMaxPerMinute;
        // Reset the counter to allow new compilations
        this.scriptsPerMinCounter = totalCompilesPerMinute;
        this.compilesAllowedPerNano = ((double) totalCompilesPerMinute) / TimeValue.timeValueMinutes(1).nanos();
    }

    /**
     * Checks if a script can be executed and compiles it if needed, or returns the previously compiled and cached script.
     */
    public CompiledScript compile(Script script, ScriptContext scriptContext) {
        Objects.requireNonNull(script);
        Objects.requireNonNull(scriptContext);

        ScriptType type = script.getType();
        String lang = script.getLang();
        String idOrCode = script.getIdOrCode();
        Map<String, String> options = script.getOptions();

        String id = idOrCode;

        // lang may be null when looking up a stored script, so we must get the
        // source to retrieve the lang before checking if the context is supported
        if (type == ScriptType.STORED) {
            // search template requests can possibly pass in the entire path instead
            // of just an id for looking up a stored script, so we parse the path and
            // check for appropriate errors
            String[] path = id.split("/");

            if (path.length == 3) {
                if (lang != null && lang.equals(path[1]) == false) {
                    throw new IllegalStateException("conflicting script languages, found [" + path[1] + "] but expected [" + lang + "]");
                }

                id = path[2];

                deprecationLogger.deprecated("use of </lang/id> [" + idOrCode + "] for looking up" +
                    " stored scripts/templates has been deprecated, use only <id> [" + id + "] instead");
            } else if (path.length != 1) {
                throw new IllegalArgumentException("illegal stored script format [" + id + "] use only <id>");
            }

            // a stored script must be pulled from the cluster state every time in case
            // the script has been updated since the last compilation
            StoredScriptSource source = getScriptFromClusterState(id, lang);
            lang = source.getLang();
            idOrCode = source.getCode();
            options = source.getOptions();
        }

        // TODO: fix this through some API or something, that's wrong
        // special exception to prevent expressions from compiling as update or mapping scripts
        boolean expression = "expression".equals(script.getLang());
        boolean notSupported = scriptContext.getKey().equals(ScriptContext.Standard.UPDATE.getKey());
        if (expression && notSupported) {
            throw new UnsupportedOperationException("scripts of type [" + script.getType() + "]," +
                " operation [" + scriptContext.getKey() + "] and lang [" + lang + "] are not supported");
        }

        ScriptEngine scriptEngine = getEngine(lang);

        if (canExecuteScript(lang, type, scriptContext) == false) {
            throw new IllegalStateException("scripts of type [" + script.getType() + "]," +
                " operation [" + scriptContext.getKey() + "] and lang [" + lang + "] are disabled");
        }

        if (logger.isTraceEnabled()) {
            logger.trace("compiling lang: [{}] type: [{}] script: {}", lang, type, idOrCode);
        }

        CacheKey cacheKey = new CacheKey(lang, idOrCode, options);
        CompiledScript compiledScript = cache.get(cacheKey);

        if (compiledScript != null) {
            return compiledScript;
        }

        // Synchronize so we don't compile scripts many times during multiple shards all compiling a script
        synchronized (this) {
            // Retrieve it again in case it has been put by a different thread
            compiledScript = cache.get(cacheKey);

            if (compiledScript == null) {
                try {
                    // Either an un-cached inline script or indexed script
                    // If the script type is inline the name will be the same as the code for identification in exceptions

                    // but give the script engine the chance to be better, give it separate name + source code
                    // for the inline case, then its anonymous: null.
                    if (logger.isTraceEnabled()) {
                        logger.trace("compiling script, type: [{}], lang: [{}], options: [{}]", type, lang, options);
                    }
                    // Check whether too many compilations have happened
                    checkCompilationLimit();
                    compiledScript = new CompiledScript(type, id, lang, scriptEngine.compile(id, idOrCode, options));
                } catch (ScriptException good) {
                    // TODO: remove this try-catch completely, when all script engines have good exceptions!
                    throw good; // its already good
                } catch (Exception exception) {
                    throw new GeneralScriptException("Failed to compile " + type + " script [" + id + "] using lang [" + lang + "]", exception);
                }

                // Since the cache key is the script content itself we don't need to
                // invalidate/check the cache if an indexed script changes.
                scriptMetrics.onCompilation();
                cache.put(cacheKey, compiledScript);
            }

            return compiledScript;
        }
    }

    /** Compiles a template. Note this will be moved to a separate TemplateService in the future. */
    public CompiledTemplate compileTemplate(Script script, ScriptContext scriptContext) {
        CompiledScript compiledScript = compile(script, scriptContext);
        return params -> (String)executable(compiledScript, params).run();
    }

    /**
     * Check whether there have been too many compilations within the last minute, throwing a circuit breaking exception if so.
     * This is a variant of the token bucket algorithm: https://en.wikipedia.org/wiki/Token_bucket
     *
     * It can be thought of as a bucket with water, every time the bucket is checked, water is added proportional to the amount of time that
     * elapsed since the last time it was checked. If there is enough water, some is removed and the request is allowed. If there is not
     * enough water the request is denied. Just like a normal bucket, if water is added that overflows the bucket, the extra water/capacity
     * is discarded - there can never be more water in the bucket than the size of the bucket.
     */
    void checkCompilationLimit() {
        long now = System.nanoTime();
        long timePassed = now - lastInlineCompileTime;
        lastInlineCompileTime = now;

        scriptsPerMinCounter += (timePassed) * compilesAllowedPerNano;

        // It's been over the time limit anyway, readjust the bucket to be level
        if (scriptsPerMinCounter > totalCompilesPerMinute) {
            scriptsPerMinCounter = totalCompilesPerMinute;
        }

        // If there is enough tokens in the bucket, allow the request and decrease the tokens by 1
        if (scriptsPerMinCounter >= 1) {
            scriptsPerMinCounter -= 1.0;
        } else {
            // Otherwise reject the request
            throw new CircuitBreakingException("[script] Too many dynamic script compilations within one minute, max: [" +
                            totalCompilesPerMinute + "/min]; please use on-disk, indexed, or scripts with parameters instead; " +
                            "this limit can be changed by the [" + SCRIPT_MAX_COMPILATIONS_PER_MINUTE.getKey() + "] setting");
        }
    }

    public boolean isLangSupported(String lang) {
        Objects.requireNonNull(lang);
        return engines.containsKey(lang);
    }

    StoredScriptSource getScriptFromClusterState(String id, String lang) {
        if (lang != null && isLangSupported(lang) == false) {
            throw new IllegalArgumentException("unable to get stored script with unsupported lang [" + lang + "]");
        }

        ScriptMetaData scriptMetadata = clusterState.metaData().custom(ScriptMetaData.TYPE);

        if (scriptMetadata == null) {
            throw new ResourceNotFoundException("unable to find script [" + id + "]" +
                (lang == null ? "" : " using lang [" + lang + "]") + " in cluster state");
        }

        StoredScriptSource source = scriptMetadata.getStoredScript(id, lang);

        if (source == null) {
            throw new ResourceNotFoundException("unable to find script [" + id + "]" +
                (lang == null ? "" : " using lang [" + lang + "]") + " in cluster state");
        }

        return source;
    }

    public void putStoredScript(ClusterService clusterService, PutStoredScriptRequest request,
                                ActionListener<PutStoredScriptResponse> listener) {
        int max = SCRIPT_MAX_SIZE_IN_BYTES.get(settings);

        if (request.content().length() > max) {
            throw new IllegalArgumentException("exceeded max allowed stored script size in bytes [" + max + "] with size [" +
                request.content().length() + "] for script [" + request.id() + "]");
        }

        StoredScriptSource source = StoredScriptSource.parse(request.lang(), request.content(), request.xContentType());

        if (isLangSupported(source.getLang()) == false) {
            throw new IllegalArgumentException("unable to put stored script with unsupported lang [" + source.getLang() + "]");
        }

        try {
            ScriptEngine scriptEngine = getEngine(source.getLang());

            if (isAnyScriptContextEnabled(source.getLang(), ScriptType.STORED)) {
                Object compiled = scriptEngine.compile(request.id(), source.getCode(), Collections.emptyMap());

                if (compiled == null) {
                    throw new IllegalArgumentException("failed to parse/compile stored script [" + request.id() + "]" +
                        (source.getCode() == null ? "" : " using code [" + source.getCode() + "]"));
                }
            } else {
                throw new IllegalArgumentException(
                    "cannot put stored script [" + request.id() + "], stored scripts cannot be run under any context");
            }
        } catch (ScriptException good) {
            throw good;
        } catch (Exception exception) {
            throw new IllegalArgumentException("failed to parse/compile stored script [" + request.id() + "]", exception);
        }

        clusterService.submitStateUpdateTask("put-script-" + request.id(),
            new AckedClusterStateUpdateTask<PutStoredScriptResponse>(request, listener) {

            @Override
            protected PutStoredScriptResponse newResponse(boolean acknowledged) {
                return new PutStoredScriptResponse(acknowledged);
            }

            @Override
            public ClusterState execute(ClusterState currentState) throws Exception {
                ScriptMetaData smd = currentState.metaData().custom(ScriptMetaData.TYPE);
                smd = ScriptMetaData.putStoredScript(smd, request.id(), source);
                MetaData.Builder mdb = MetaData.builder(currentState.getMetaData()).putCustom(ScriptMetaData.TYPE, smd);

                return ClusterState.builder(currentState).metaData(mdb).build();
            }
        });
    }

    public void deleteStoredScript(ClusterService clusterService, DeleteStoredScriptRequest request,
                                   ActionListener<DeleteStoredScriptResponse> listener) {
        if (request.lang() != null && isLangSupported(request.lang()) == false) {
            throw new IllegalArgumentException("unable to delete stored script with unsupported lang [" + request.lang() +"]");
        }

        clusterService.submitStateUpdateTask("delete-script-" + request.id(),
            new AckedClusterStateUpdateTask<DeleteStoredScriptResponse>(request, listener) {

            @Override
            protected DeleteStoredScriptResponse newResponse(boolean acknowledged) {
                return new DeleteStoredScriptResponse(acknowledged);
            }

            @Override
            public ClusterState execute(ClusterState currentState) throws Exception {
                ScriptMetaData smd = currentState.metaData().custom(ScriptMetaData.TYPE);
                smd = ScriptMetaData.deleteStoredScript(smd, request.id(), request.lang());
                MetaData.Builder mdb = MetaData.builder(currentState.getMetaData()).putCustom(ScriptMetaData.TYPE, smd);

                return ClusterState.builder(currentState).metaData(mdb).build();
            }
        });
    }

    public StoredScriptSource getStoredScript(ClusterState state, GetStoredScriptRequest request) {
        ScriptMetaData scriptMetadata = state.metaData().custom(ScriptMetaData.TYPE);

        if (scriptMetadata != null) {
            return scriptMetadata.getStoredScript(request.id(), request.lang());
        } else {
            return null;
        }
    }

    /**
     * Executes a previously compiled script provided as an argument
     */
    public ExecutableScript executable(CompiledScript compiledScript, Map<String, Object> params) {
        return getEngine(compiledScript.lang()).executable(compiledScript, params);
    }

    /**
     * Compiles (or retrieves from cache) and executes the provided search script
     */
    public SearchScript search(SearchLookup lookup, Script script, ScriptContext scriptContext) {
        CompiledScript compiledScript = compile(script, scriptContext);
        return search(lookup, compiledScript, script.getParams());
    }

    /**
     * Binds provided parameters to a compiled script returning a
     * {@link SearchScript} ready for execution
     */
    public SearchScript search(SearchLookup lookup, CompiledScript compiledScript,  Map<String, Object> params) {
        return getEngine(compiledScript.lang()).search(compiledScript, lookup, params);
    }

    private boolean isAnyScriptContextEnabled(String lang, ScriptType scriptType) {
        for (ScriptContext scriptContext : scriptContextRegistry.scriptContexts()) {
            if (canExecuteScript(lang, scriptType, scriptContext)) {
                return true;
            }
        }
        return false;
    }

    private boolean canExecuteScript(String lang, ScriptType scriptType, ScriptContext scriptContext) {
        assert lang != null;
        if (scriptContextRegistry.isSupportedContext(scriptContext.getKey()) == false) {
            throw new IllegalArgumentException("script context [" + scriptContext.getKey() + "] not supported");
        }
        return scriptModes.getScriptEnabled(lang, scriptType, scriptContext);
    }

    public ScriptStats stats() {
        return scriptMetrics.stats();
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        clusterState = event.state();
    }

    /**
     * A small listener for the script cache that calls each
     * {@code ScriptEngine}'s {@code scriptRemoved} method when the
     * script has been removed from the cache
     */
    private class ScriptCacheRemovalListener implements RemovalListener<CacheKey, CompiledScript> {
        @Override
        public void onRemoval(RemovalNotification<CacheKey, CompiledScript> notification) {
            if (logger.isDebugEnabled()) {
                logger.debug("removed {} from cache, reason: {}", notification.getValue(), notification.getRemovalReason());
            }
            scriptMetrics.onCacheEviction();
        }
    }

    private static final class CacheKey {
        final String lang;
        final String idOrCode;
        final Map<String, String> options;

        private CacheKey(String lang, String idOrCode, Map<String, String> options) {
            this.lang = lang;
            this.idOrCode = idOrCode;
            this.options = options;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            CacheKey cacheKey = (CacheKey)o;

            if (lang != null ? !lang.equals(cacheKey.lang) : cacheKey.lang != null) return false;
            if (!idOrCode.equals(cacheKey.idOrCode)) return false;
            return options != null ? options.equals(cacheKey.options) : cacheKey.options == null;

        }

        @Override
        public int hashCode() {
            int result = lang != null ? lang.hashCode() : 0;
            result = 31 * result + idOrCode.hashCode();
            result = 31 * result + (options != null ? options.hashCode() : 0);
            return result;
        }
    }
}
