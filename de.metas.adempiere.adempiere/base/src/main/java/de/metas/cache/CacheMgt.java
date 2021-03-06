/******************************************************************************
 * Product: Adempiere ERP & CRM Smart Business Solution *
 * Copyright (C) 1999-2006 ComPiere, Inc. All Rights Reserved. *
 * This program is free software; you can redistribute it and/or modify it *
 * under the terms version 2 of the GNU General Public License as published *
 * by the Free Software Foundation. This program is distributed in the hope *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. *
 * See the GNU General Public License for more details. *
 * You should have received a copy of the GNU General Public License along *
 * with this program; if not, write to the Free Software Foundation, Inc., *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA. *
 * For the text or an alternative of this public license, you may reach us *
 * ComPiere, Inc., 2620 Augustine Dr. #245, Santa Clara, CA 95054, USA *
 * or via info@compiere.org or http://www.compiere.org/license.html *
 *****************************************************************************/
package de.metas.cache;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.adempiere.ad.trx.api.ITrx;
import org.adempiere.ad.trx.api.ITrxListenerManager.TrxEventTiming;
import org.adempiere.ad.trx.api.ITrxManager;
import org.adempiere.ad.trx.api.OnTrxMissingPolicy;
import org.adempiere.util.jmx.JMXRegistry;
import org.adempiere.util.jmx.JMXRegistry.OnJMXAlreadyExistsPolicy;
import org.adempiere.util.lang.impl.TableRecordReference;
import org.compiere.Adempiere;
import org.slf4j.Logger;

import com.google.common.base.MoreObjects;
import com.google.common.base.Predicates;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.MapMaker;
import com.google.common.collect.Maps;

import de.metas.cache.model.CacheInvalidateMultiRequest;
import de.metas.cache.model.CacheInvalidateRequest;
import de.metas.logging.LogManager;
import de.metas.util.Check;
import de.metas.util.Services;
import lombok.NonNull;

/**
 * Adempiere Cache Management
 *
 * @author Jorg Janke
 * @version $Id: CacheMgt.java,v 1.2 2006/07/30 00:54:35 jjanke Exp $
 */
public final class CacheMgt
{
	public static final CacheMgt get()
	{
		return instance;
	}

	private static final CacheMgt instance = new CacheMgt();

	public static final String JMX_BASE_NAME = "de.metas.cache";

	private final ConcurrentHashMap<CacheLabel, CachesGroup> cachesByLabel = new ConcurrentHashMap<>();

	private final CopyOnWriteArrayList<ICacheResetListener> globalCacheResetListeners = new CopyOnWriteArrayList<>();
	private final ConcurrentMap<String, CopyOnWriteArrayList<ICacheResetListener>> cacheResetListenersByTableName = new ConcurrentHashMap<>();

	/* package */ static final transient Logger logger = LogManager.getLogger(CacheMgt.class);

	private final AtomicBoolean cacheResetRunning = new AtomicBoolean();
	private final AtomicLong lastCacheReset = new AtomicLong();

	private CacheMgt()
	{
		JMXRegistry.get().registerJMX(new JMXCacheMgt(), OnJMXAlreadyExistsPolicy.Replace);
	}

	/**
	 * Enable caches for the given table to be invalidated by remote events.<br>
	 * Example: if a user somewhere else opens/closes a period, we can allow the system to invalidate all the local caches to avoid it becoming stale.
	 */
	public final void enableRemoteCacheInvalidationForTableName(final String tableName)
	{
		CacheInvalidationRemoteHandler.instance.enableForTableName(tableName);
	}

	private CachesGroup getCachesGroup(@NonNull final CacheLabel label)
	{
		return cachesByLabel.computeIfAbsent(label, CachesGroup::new);
	}

	private CachesGroup getCachesGroupIfPresent(@NonNull final CacheLabel label)
	{
		return cachesByLabel.get(label);
	}

	public void register(@NonNull final CacheInterface instance)
	{
		final Boolean registerWeak = null; // auto
		register(instance, registerWeak);
	}

	private void register(@NonNull final CacheInterface cache, final Boolean registerWeak)
	{
		// FIXME: consider register weak flag

		final Set<CacheLabel> labels = cache.getLabels();
		Check.assumeNotEmpty(labels, "labels is not empty");

		labels.stream()
				.map(this::getCachesGroup)
				.forEach(cacheGroup -> cacheGroup.addCache(cache));
	}

	public void unregister(final CacheInterface cache)
	{
		cache.getLabels()
				.stream()
				.map(this::getCachesGroup)
				.forEach(cacheGroup -> cacheGroup.removeCache(cache));
	}

	public Set<CacheLabel> getCacheLabels()
	{
		return ImmutableSet.copyOf(cachesByLabel.keySet());
	}

	public Set<String> getTableNamesToBroadcast()
	{
		return CacheInvalidationRemoteHandler.instance.getTableNamesToBroadcast();
	}

	/** @return last time cache reset timestamp */
	public long getLastCacheReset()
	{
		return lastCacheReset.get();
	}

	/**
	 * Invalidate ALL cached entries of all registered {@link CacheInterface}s.
	 *
	 * @return how many cache entries were invalidated
	 */
	public long reset()
	{
		final Stopwatch stopwatch = Stopwatch.createStarted();

		// Do nothing if already running (i.e. avoid recursion)
		if (cacheResetRunning.getAndSet(true))
		{
			logger.trace("Avoid calling full cache reset again. We are currently doing it...");
			return 0;
		}

		long total = 0;
		try
		{
			total = cachesByLabel.values()
					.stream()
					.mapToLong(cachesGroup -> cachesGroup.invalidateAllNoFail())
					.sum();

			fireGlobalCacheResetListeners(CacheInvalidateMultiRequest.all());

			lastCacheReset.incrementAndGet();
		}
		finally
		{
			cacheResetRunning.set(false);
			stopwatch.stop();
		}

		logger.info("Reset all: cache instances invalidated ({} cached items invalidated). Took {}", total, stopwatch);
		return total;
	}

	private void fireGlobalCacheResetListeners(final CacheInvalidateMultiRequest multiRequest)
	{
		globalCacheResetListeners.forEach(listener -> fireCacheResetListenerNoFail(listener, multiRequest));

		if (multiRequest.isResetAll())
		{
			cacheResetListenersByTableName.values()
					.stream()
					.flatMap(listeners -> listeners.stream())
					.forEach(listener -> fireCacheResetListenerNoFail(listener, multiRequest));
		}
		else
		{
			multiRequest.getTableNamesEffective()
					.stream()
					.map(cacheResetListenersByTableName::get)
					.filter(Predicates.notNull())
					.flatMap(listeners -> listeners.stream())
					.forEach(listener -> fireCacheResetListenerNoFail(listener, multiRequest));
		}
	}

	private void fireCacheResetListenerNoFail(final ICacheResetListener listener, final CacheInvalidateMultiRequest multiRequest)
	{
		try
		{
			listener.reset(multiRequest);
		}
		catch (final Exception ex)
		{
			logger.warn("Failed firing {} for {}. Ignored.", listener, multiRequest, ex);
		}
	}

	/**
	 * Invalidate all cached entries for given TableName.
	 *
	 * @param tableName table name
	 * @return how many cache entries were invalidated
	 */
	public long reset(final String tableName)
	{
		final CacheInvalidateMultiRequest request = CacheInvalidateMultiRequest.allRecordsForTable(tableName);
		return reset(request, ResetMode.LOCAL_AND_BROADCAST);
	}	// reset

	/**
	 * Invalidate all cached entries for given TableName.
	 *
	 * The event won't be broadcasted.
	 *
	 * @param tableName table name
	 * @return how many cache entries were invalidated
	 */
	public long resetLocal(final String tableName)
	{
		final CacheInvalidateMultiRequest request = CacheInvalidateMultiRequest.allRecordsForTable(tableName);
		return reset(request, ResetMode.LOCAL);
	}	// reset

	/**
	 * Invalidate all cached entries for given TableName/Record_ID.
	 *
	 * @param tableName table name
	 * @param recordId record if applicable or negative for all
	 * @return how many cache entries were invalidated
	 */
	public long reset(final String tableName, final int recordId)
	{
		final CacheInvalidateMultiRequest request = CacheInvalidateMultiRequest.fromTableNameAndRecordId(tableName, recordId);

		final ResetMode mode = Adempiere.isUnitTestMode()
				? ResetMode.LOCAL
				: ResetMode.LOCAL_AND_BROADCAST;

		return reset(request, mode);
	}

	/**
	 * Reset cache for TableName/Record_ID when given transaction is committed.
	 *
	 * If no transaction was given or given transaction was not found, the cache is reset right away.
	 *
	 * @param trxName
	 * @param tableName
	 * @param recordId
	 */
	public void resetLocalNowAndBroadcastOnTrxCommit(final String trxName, final CacheInvalidateMultiRequest request)
	{
		final ITrxManager trxManager = Services.get(ITrxManager.class);
		final ITrx trx = trxManager.get(trxName, OnTrxMissingPolicy.ReturnTrxNone);
		if (!trxManager.isActive(trx))
		{
			reset(request, ResetMode.LOCAL_AND_BROADCAST);
		}
		else
		{
			reset(request, ResetMode.LOCAL);
			RecordsToResetOnTrxCommitCollector.getCreate(trx).addRecord(request, ResetMode.JUST_BROADCAST);
		}
	}

	static enum ResetMode
	{
		LOCAL, LOCAL_AND_BROADCAST, JUST_BROADCAST;

		public boolean isResetLocal()
		{
			return this == LOCAL || this == LOCAL_AND_BROADCAST;
		}

		public boolean isBroadcast()
		{
			return this == LOCAL_AND_BROADCAST || this == JUST_BROADCAST;
		}
	}

	/**
	 * Invalidate all cached entries for given TableName/Record_ID.
	 *
	 * @param tableName
	 * @param recordId
	 * @param broadcast true if we shall also broadcast this remotely.
	 * @return how many cache entries were invalidated (estimated!)
	 */
	final long reset(@NonNull final CacheInvalidateMultiRequest multiRequest, @NonNull final ResetMode mode)
	{
		final long resetCount;
		if (mode.isResetLocal())
		{
			resetCount = invalidateForMultiRequest(multiRequest);
			fireGlobalCacheResetListeners(multiRequest);
		}
		else
		{
			resetCount = 0;
		}

		//
		// Broadcast cache invalidation.
		// We do this, even if we don't have any cache interface registered locally, because there might be remotely.
		if (mode.isBroadcast())
		{
			CacheInvalidationRemoteHandler.instance.postEvent(multiRequest);
		}

		return resetCount;
	}	// reset

	private final long invalidateForMultiRequest(final CacheInvalidateMultiRequest multiRequest)
	{
		if (multiRequest.isResetAll())
		{
			return reset();
		}

		int total = 0;
		for (final CacheInvalidateRequest request : multiRequest.getRequests())
		{
			final long totalPerRequest = invalidateForRequest(request);
			total += totalPerRequest;
		}

		return total;
	}

	private final long invalidateForRequest(final CacheInvalidateRequest request)
	{
		if (request.isAllRecords())
		{
			final CacheLabel label = CacheLabel.ofTableName(request.getTableNameEffective());
			final CachesGroup cachesGroup = getCachesGroupIfPresent(label);
			if (cachesGroup == null)
			{
				return 0;
			}

			return cachesGroup.invalidateAllNoFail();
		}
		else
		{
			long resetCount = 0;

			final TableRecordReference childRecordRef = request.getChildRecordOrNull();
			if (childRecordRef != null)
			{
				resetCount += invalidateForRecord(childRecordRef);
			}
			final TableRecordReference rootRecordRef = request.getRootRecordOrNull();
			if (rootRecordRef != null)
			{
				resetCount += invalidateForRecord(rootRecordRef);
			}

			return resetCount;
		}
	}

	private final long invalidateForRecord(final TableRecordReference recordRef)
	{
		final CacheLabel label = CacheLabel.ofTableName(recordRef.getTableName());
		final CachesGroup cachesGroup = getCachesGroupIfPresent(label);
		if (cachesGroup == null)
		{
			return 0;
		}

		return cachesGroup.invalidateForRecordNoFail(recordRef);
	}

	/**
	 * @return how many cached elements do we have in total
	 */
	private long computeTotalSize()
	{
		return cachesByLabel.values()
				.stream()
				.mapToLong(CachesGroup::computeTotalSize)
				.sum();
	}

	/**
	 * String Representation
	 *
	 * @return info
	 */
	@Override
	public String toString()
	{
		final StringBuilder sb = new StringBuilder("CacheMgt[");
		sb.append("Instances=")
				.append(cachesByLabel.size())
				.append("]");
		return sb.toString();
	}	// toString

	/**
	 * Extended String Representation
	 *
	 * @return info
	 */
	public String toStringX()
	{
		final StringBuilder sb = new StringBuilder("CacheMgt[");
		sb.append("Instances=")
				.append(cachesByLabel.size())
				.append(", Elements=").append(computeTotalSize())
				.append("]");
		return sb.toString();
	}	// toString

	public void addCacheResetListener(@NonNull final ICacheResetListener cacheResetListener)
	{
		globalCacheResetListeners.addIfAbsent(cacheResetListener);
	}

	/**
	 * Adds an listener which will be fired when the cache for given table is about to be reset.
	 */
	public void addCacheResetListener(@NonNull final String tableName, @NonNull final ICacheResetListener cacheResetListener)
	{
		cacheResetListenersByTableName
				.computeIfAbsent(tableName, k -> new CopyOnWriteArrayList<>())
				.addIfAbsent(cacheResetListener);
	}

	public boolean removeCacheResetListener(@NonNull final String tableName, @NonNull final ICacheResetListener cacheResetListener)
	{
		final CopyOnWriteArrayList<ICacheResetListener> cacheResetListeners = cacheResetListenersByTableName.get(tableName);
		if (cacheResetListeners == null)
		{
			return false;
		}

		return cacheResetListeners.remove(cacheResetListener);
	}

	/** Collects records that needs to be removed from cache when a given transaction is committed */
	private static final class RecordsToResetOnTrxCommitCollector
	{
		/** Gets/creates the records collector which needs to be reset when transaction is committed */
		public static final RecordsToResetOnTrxCommitCollector getCreate(final ITrx trx)
		{
			return trx.getProperty(TRX_PROPERTY, () -> {

				final RecordsToResetOnTrxCommitCollector collector = new RecordsToResetOnTrxCommitCollector();

				// Listens {@link ITrx}'s after-commit and fires enqueued cache invalidation requests
				trx.getTrxListenerManager()
						.newEventListener(TrxEventTiming.AFTER_COMMIT)
						.invokeMethodJustOnce(false) // invoke the handling method on *every* commit, because that's how it was and I can't check now if it's really needed
						.registerHandlingMethod(innerTrx -> {

							final RecordsToResetOnTrxCommitCollector innerCollector = innerTrx.getProperty(TRX_PROPERTY);
							if (innerCollector == null)
							{
								return;
							}
							innerCollector.sendRequestsAndClear();
						});

				return collector;
			});
		}

		private static final String TRX_PROPERTY = RecordsToResetOnTrxCommitCollector.class.getName();

		private final Map<CacheInvalidateRequest, ResetMode> request2resetMode = Maps.newConcurrentMap();

		/** Enqueues a record */
		public final void addRecord(@NonNull final CacheInvalidateMultiRequest multiRequest, @NonNull final ResetMode resetMode)
		{
			multiRequest.getRequests()
					.forEach(request -> request2resetMode.put(request, resetMode));
			logger.debug("Scheduled cache invalidation on transaction commit: {} ({})", multiRequest, resetMode);
		}

		/** Reset the cache for all enqueued records */
		private void sendRequestsAndClear()
		{
			if (request2resetMode.isEmpty())
			{
				return;
			}

			final CacheMgt cacheMgt = CacheMgt.get();

			final ImmutableList.Builder<CacheInvalidateRequest> resetLocalRequestsBuilder = ImmutableList.builder();
			final ImmutableList.Builder<CacheInvalidateRequest> broadcastRequestsBuilder = ImmutableList.builder();
			request2resetMode.forEach((request, resetMode) -> {
				if (resetMode.isResetLocal())
				{
					resetLocalRequestsBuilder.add(request);
				}
				if (resetMode.isBroadcast())
				{
					broadcastRequestsBuilder.add(request);
				}
			});

			final ImmutableList<CacheInvalidateRequest> resetLocalRequests = resetLocalRequestsBuilder.build();
			if (!resetLocalRequests.isEmpty())
			{
				cacheMgt.reset(CacheInvalidateMultiRequest.of(resetLocalRequests), ResetMode.LOCAL);
			}

			final ImmutableList<CacheInvalidateRequest> broadcastRequests = broadcastRequestsBuilder.build();
			if (!broadcastRequests.isEmpty())
			{
				cacheMgt.reset(CacheInvalidateMultiRequest.of(broadcastRequests), ResetMode.JUST_BROADCAST);
			}

			request2resetMode.clear();
		}
	}

	private static class CachesGroup
	{
		private final CacheLabel label;
		private final ConcurrentMap<Long, CacheInterface> caches = new MapMaker()
				.weakValues()
				.makeMap();

		public CachesGroup(@NonNull final CacheLabel label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return MoreObjects.toStringHelper(this)
					.add("label", label)
					.add("size", caches.size())
					.toString();
		}

		public void addCache(@NonNull final CacheInterface cache)
		{
			caches.put(cache.getCacheId(), cache);
		}

		public void removeCache(@NonNull final CacheInterface cache)
		{
			caches.remove(cache.getCacheId());
		}

		private final Stream<CacheInterface> streamCaches()
		{
			return caches.values()
					.stream()
					.filter(Predicates.notNull());
		}

		public long computeTotalSize()
		{
			return streamCaches()
					.mapToLong(CacheInterface::size)
					.sum();
		}

		public long invalidateAllNoFail()
		{
			return streamCaches()
					.mapToLong(cache -> invalidateNoFail(cache))
					.sum();
		}

		public long invalidateForRecordNoFail(final TableRecordReference recordRef)
		{
			return streamCaches()
					.mapToLong(cache -> invalidateNoFail(cache, recordRef))
					.sum();
		}

		private static final long invalidateNoFail(final CacheInterface cacheInstance, final TableRecordReference recordRef)
		{
			try
			{
				return cacheInstance.resetForRecordId(recordRef);
			}
			catch (final Exception ex)
			{
				// log but don't fail
				logger.warn("Error while reseting {} for {}. Ignored.", cacheInstance, recordRef, ex);
				return 0;
			}
		}

		private static final long invalidateNoFail(final CacheInterface cacheInstance)
		{
			if (cacheInstance == null)
			{
				return 0;
			}

			try
			{
				return cacheInstance.reset();
			}
			catch (final Exception ex)
			{
				// log but don't fail
				logger.warn("Error while reseting {}. Ignored.", cacheInstance, ex);
				return 0;
			}
		}
	}
}
