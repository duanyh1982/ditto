/*
 * Copyright (c) 2017 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/index.php
 *
 * Contributors:
 *    Bosch Software Innovations GmbH - initial contribution
 */
package org.eclipse.ditto.services.thingsearch.updater.actors;

import static org.eclipse.ditto.services.thingsearch.persistence.PersistenceConstants.POLICIES_SYNC_STATE_COLLECTION_NAME;
import static org.eclipse.ditto.services.thingsearch.persistence.PersistenceConstants.THINGS_SYNC_STATE_COLLECTION_NAME;

import java.net.ConnectException;
import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;

import org.eclipse.ditto.model.base.exceptions.DittoRuntimeException;
import org.eclipse.ditto.services.thingsearch.common.util.ConfigKeys;
import org.eclipse.ditto.services.thingsearch.persistence.write.ThingsSearchUpdaterPersistence;
import org.eclipse.ditto.services.thingsearch.persistence.write.impl.MongoEventToPersistenceStrategyFactory;
import org.eclipse.ditto.services.thingsearch.persistence.write.impl.MongoThingsSearchUpdaterPersistence;
import org.eclipse.ditto.services.utils.akka.streaming.StreamConsumerSettings;
import org.eclipse.ditto.services.utils.akka.streaming.StreamMetadataPersistence;
import org.eclipse.ditto.services.utils.distributedcache.actors.CacheFacadeActor;
import org.eclipse.ditto.services.utils.distributedcache.actors.CacheRole;
import org.eclipse.ditto.services.utils.persistence.mongo.MongoClientWrapper;
import org.eclipse.ditto.services.utils.persistence.mongo.streaming.MongoSearchSyncPersistence;

import com.typesafe.config.Config;

import akka.actor.AbstractActor;
import akka.actor.ActorKilledException;
import akka.actor.ActorRef;
import akka.actor.InvalidActorNameException;
import akka.actor.OneForOneStrategy;
import akka.actor.PoisonPill;
import akka.actor.Props;
import akka.actor.Status;
import akka.actor.SupervisorStrategy;
import akka.cluster.pubsub.DistributedPubSubMediator;
import akka.cluster.sharding.ShardRegion;
import akka.cluster.singleton.ClusterSingletonManager;
import akka.cluster.singleton.ClusterSingletonManagerSettings;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.DeciderBuilder;
import akka.japi.pf.ReceiveBuilder;
import akka.pattern.AskTimeoutException;
import akka.pattern.CircuitBreaker;
import akka.stream.ActorMaterializer;

/**
 * Our "Parent" Actor which takes care of supervision of all other Actors in our system.
 */
public final class SearchUpdaterRootActor extends AbstractActor {

    /**
     * The name of this Actor in the ActorSystem.
     */
    public static final String ACTOR_NAME = "searchUpdaterRoot";

    private static final String RESTART_MSG = "Restarting child...";

    private final LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    private final SupervisorStrategy strategy = new OneForOneStrategy(true, DeciderBuilder //
            .match(NullPointerException.class, e -> {
                log.error(e, "NullPointer in child actor: {}", e.getMessage());
                log.info(RESTART_MSG);
                return SupervisorStrategy.restart();
            }).match(IllegalArgumentException.class, e -> {
                log.warning("Illegal Argument in child actor: {}", e.getMessage());
                return SupervisorStrategy.resume();
            }).match(IllegalStateException.class, e -> {
                log.warning("Illegal State in child actor: {}", e.getMessage());
                return SupervisorStrategy.resume();
            }).match(NoSuchElementException.class, e -> {
                log.warning("NoSuchElement in child actor: {}", e.getMessage());
                return SupervisorStrategy.resume();
            }).match(AskTimeoutException.class, e -> {
                log.warning("AskTimeoutException in child actor: {}", e.getMessage());
                return SupervisorStrategy.resume();
            }).match(ConnectException.class, e -> {
                log.warning("ConnectException in child actor: {}", e.getMessage());
                log.info(RESTART_MSG);
                return SupervisorStrategy.restart();
            }).match(InvalidActorNameException.class, e -> {
                log.warning("InvalidActorNameException in child actor: {}", e.getMessage());
                return SupervisorStrategy.resume();
            }).match(ActorKilledException.class, e -> {
                log.error(e, "ActorKilledException in child actor: {}", e.message());
                log.info(RESTART_MSG);
                return SupervisorStrategy.restart();
            }).match(DittoRuntimeException.class, e -> {
                log.error(e,
                        "DittoRuntimeException '{}' should not be escalated to SearchUpdaterRootActor. Simply resuming Actor.",
                        e.getErrorCode());
                return SupervisorStrategy.resume();
            }).match(Throwable.class, e -> {
                log.error(e, "Escalating above root actor!");
                return SupervisorStrategy.escalate();
            }).matchAny(e -> {
                log.error("Unknown message:'{}'! Escalating above root actor!", e);
                return SupervisorStrategy.escalate();
            }).build());

    private final ActorRef thingsUpdaterActor;

    private SearchUpdaterRootActor(final Config config, final ActorRef pubSubMediator) {
        final int numberOfShards = config.getInt(ConfigKeys.CLUSTER_NUMBER_OF_SHARDS);

        final MongoClientWrapper mongoClientWrapper = MongoClientWrapper.newInstance(config);
        final ThingsSearchUpdaterPersistence searchUpdaterPersistence =
                new MongoThingsSearchUpdaterPersistence(mongoClientWrapper, log,
                        MongoEventToPersistenceStrategyFactory.getInstance());

        final int maxFailures = config.getInt(ConfigKeys.MONGO_CIRCUIT_BREAKER_FAILURES);
        final Duration callTimeout = config.getDuration(ConfigKeys.MONGO_CIRCUIT_BREAKER_TIMEOUT_CALL);
        final Duration resetTimeout = config.getDuration(ConfigKeys.MONGO_CIRCUIT_BREAKER_TIMEOUT_RESET);
        final CircuitBreaker circuitBreaker =
                new CircuitBreaker(getContext().dispatcher(), getContext().system().scheduler(), maxFailures,
                        scala.concurrent.duration.Duration.create(callTimeout.getSeconds(), TimeUnit.SECONDS),
                        scala.concurrent.duration.Duration.create(resetTimeout.getSeconds(), TimeUnit.SECONDS));
        circuitBreaker.onOpen(() -> log.warning(
                "The circuit breaker for this search updater instance is open which means that all ThingUpdaters" +
                        " won't process any messages until the circuit breaker is closed again"));
        circuitBreaker.onClose(() -> log.info(
                "The circuit breaker for this search updater instance is closed again. Therefore all ThingUpdaters" +
                        " process events again"));

        pubSubMediator.tell(new DistributedPubSubMediator.Put(getSelf()), getSelf());

        final boolean eventProcessingActive = config.getBoolean(ConfigKeys.EVENT_PROCESSING_ACTIVE);
        if (!eventProcessingActive) {
            log.warning("Event processing is disabled.");
        }

        final boolean cacheUpdatesActive = config.getBoolean(ConfigKeys.CACHE_UPDATES_ACTIVE);
        if (!cacheUpdatesActive) {
            log.warning("Cache-updates are disabled.");
        }

        final ActorRef thingCacheFacade = cacheUpdatesActive ?
                startChildActor(CacheFacadeActor.actorNameFor(CacheRole.THING),
                        CacheFacadeActor.props(CacheRole.THING, config)) : null;
        final ActorRef policyCacheFacade = cacheUpdatesActive ?
                startChildActor(CacheFacadeActor.actorNameFor(CacheRole.POLICY),
                        CacheFacadeActor.props(CacheRole.POLICY, config)) : null;

        final Duration thingUpdaterActivityCheckInterval =
                config.getDuration(ConfigKeys.THINGS_ACTIVITY_CHECK_INTERVAL);
        final ShardRegionFactory shardRegionFactory = ShardRegionFactory.getInstance(getContext().getSystem());
        thingsUpdaterActor = startChildActor(ThingsUpdater.ACTOR_NAME, ThingsUpdater
                .props(numberOfShards, shardRegionFactory, searchUpdaterPersistence, circuitBreaker,
                        eventProcessingActive,
                        thingUpdaterActivityCheckInterval, thingCacheFacade,
                        policyCacheFacade));

        final boolean thingsSynchronizationActive = config.getBoolean(ConfigKeys.THINGS_SYNCER_ACTIVE);
        if (thingsSynchronizationActive) {
            final ActorMaterializer materializer = ActorMaterializer.create(getContext().getSystem());

            final StreamMetadataPersistence thingsSyncPersistence =
                    MongoSearchSyncPersistence.initializedInstance(THINGS_SYNC_STATE_COLLECTION_NAME,
                            mongoClientWrapper, materializer);

            final StreamConsumerSettings streamConsumerSettings = createThingsStreamConsumerSettings(config);

            startClusterSingletonActor(ThingsStreamSupervisorCreator.ACTOR_NAME,
                    ThingsStreamSupervisorCreator.props(thingsUpdaterActor, pubSubMediator, thingsSyncPersistence,
                            materializer, streamConsumerSettings));
        } else {
            log.warning("Things synchronization is not active");
        }

        final boolean policiesSynchronizationActive = config.getBoolean(ConfigKeys.POLICIES_SYNCER_ACTIVE);
        if (policiesSynchronizationActive) {
            final ActorMaterializer materializer = ActorMaterializer.create(getContext().getSystem());

            final StreamMetadataPersistence policiesSyncPersistence =
                    MongoSearchSyncPersistence.initializedInstance(POLICIES_SYNC_STATE_COLLECTION_NAME,
                            mongoClientWrapper, materializer);

            final StreamConsumerSettings streamConsumerSettings = createPoliciesStreamConsumerSettings(config);

            startClusterSingletonActor(PoliciesStreamSupervisorCreator.ACTOR_NAME,
                    PoliciesStreamSupervisorCreator.props(thingsUpdaterActor, pubSubMediator, policiesSyncPersistence,
                            materializer, streamConsumerSettings, searchUpdaterPersistence));
        } else {
            log.warning("Policies synchronization is not active");
        }
    }

    private static StreamConsumerSettings createThingsStreamConsumerSettings(final Config config) {
        final Duration startOffset = config.getDuration(ConfigKeys.THINGS_SYNCER_START_OFFSET);
        final Duration streamInterval = config.getDuration(ConfigKeys.THINGS_SYNCER_STREAM_INTERVAL);
        final Duration initialStartOffset = config.getDuration(ConfigKeys.THINGS_SYNCER_INITIAL_START_OFFSET);
        final Duration maxIdleTime = config.getDuration(ConfigKeys.THINGS_SYNCER_MAX_IDLE_TIME);
        final Duration streamingActorTimeout = config.getDuration(ConfigKeys.THINGS_SYNCER_STREAMING_ACTOR_TIMEOUT);
        final int elementsStreamedPerBatch = config.getInt(ConfigKeys.THINGS_SYNCER_ELEMENTS_STREAMED_PER_BATCH);
        final Duration outdatedWarningOffset = config.getDuration(ConfigKeys.THINGS_SYNCER_OUTDATED_WARNING_OFFSET);

        return StreamConsumerSettings.of(startOffset, streamInterval, initialStartOffset, maxIdleTime,
                streamingActorTimeout, elementsStreamedPerBatch, outdatedWarningOffset);
    }

    private static StreamConsumerSettings createPoliciesStreamConsumerSettings(final Config config) {
        final Duration startOffset = config.getDuration(ConfigKeys.POLICIES_SYNCER_START_OFFSET);
        final Duration streamInterval = config.getDuration(ConfigKeys.POLICIES_SYNCER_STREAM_INTERVAL);
        final Duration initialStartOffset = config.getDuration(ConfigKeys.POLICIES_SYNCER_INITIAL_START_OFFSET);
        final Duration maxIdleTime = config.getDuration(ConfigKeys.POLICIES_SYNCER_MAX_IDLE_TIME);
        final Duration streamingActorTimeout = config.getDuration(ConfigKeys.POLICIES_SYNCER_STREAMING_ACTOR_TIMEOUT);
        final int elementsStreamedPerBatch = config.getInt(ConfigKeys.POLICIES_SYNCER_ELEMENTS_STREAMED_PER_BATCH);
        final Duration outdatedWarningOffset = config.getDuration(ConfigKeys.POLICIES_SYNCER_OUTDATED_WARNING_OFFSET);

        return StreamConsumerSettings.of(startOffset, streamInterval, initialStartOffset, maxIdleTime,
                streamingActorTimeout, elementsStreamedPerBatch, outdatedWarningOffset);
    }

    /**
     * Creates Akka configuration object Props for this SearchUpdaterRootActor.
     *
     * @param config the configuration settings of the Search Updater Service.
     * @param pubSubMediator the PubSub mediator Actor.
     * @return a Props object to create this actor.
     */
    public static Props props(final Config config, final ActorRef pubSubMediator) {
        return Props.create(SearchUpdaterRootActor.class, new Creator<SearchUpdaterRootActor>() {
            private static final long serialVersionUID = 1L;

            @Override
            public SearchUpdaterRootActor create() throws Exception {
                return new SearchUpdaterRootActor(config, pubSubMediator);
            }
        });
    }

    @Override
    public Receive createReceive() {
        return ReceiveBuilder.create()
                .matchEquals(ShardRegion.getShardRegionStateInstance(), getShardRegionState ->
                        thingsUpdaterActor.forward(getShardRegionState, getContext()))
                .match(Status.Failure.class, f -> log.error(f.cause(), "Got failure: {}", f))
                .matchAny(m -> {
                    log.warning("Unknown message: {}", m);
                    unhandled(m);
                })
                .build();
    }

    private ActorRef startChildActor(final String actorName, final Props props) {
        log.info("Starting child actor '{}'", actorName);
        return getContext().actorOf(props, actorName);
    }

    private void startClusterSingletonActor(final String actorName, final Props props) {
        final ClusterSingletonManagerSettings settings =
                ClusterSingletonManagerSettings.create(getContext().system()).withRole(ConfigKeys.SEARCH_ROLE);
        getContext().actorOf(ClusterSingletonManager.props(props, PoisonPill.getInstance(), settings), actorName);
    }

    @Override
    public SupervisorStrategy supervisorStrategy() {
        return strategy;
    }

}
