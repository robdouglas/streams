package org.apache.streams.elasticsearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Objects;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Queues;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.typesafe.config.Config;
import org.apache.streams.config.StreamsConfigurator;
import org.apache.streams.core.StreamsDatum;
import org.apache.streams.core.StreamsPersistReader;
import org.apache.streams.core.StreamsResultSet;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.index.query.FilterBuilder;
import org.elasticsearch.index.query.FilterBuilders;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.sort.SortBuilders;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;

/**************************************************************************************************************
 * Authors:
 * smashew
 * steveblackmon
 **************************************************************************************************************/

public class ElasticsearchPersistReader implements StreamsPersistReader, Iterable<SearchHit>, Iterator<SearchHit>
{
    public final static String STREAMS_ID = "ElasticsearchPersistReader";

    private final static Logger LOGGER = LoggerFactory.getLogger(ElasticsearchPersistReader.class);

    protected volatile Queue<StreamsDatum> persistQueue;

    private static final int SCROLL_POSITION_NOT_INITIALIZED = -3;
    private static final Integer DEFAULT_BATCH_SIZE = 500;
    private static final String DEFAULT_SCROLL_TIMEOUT = "5m";

    private ElasticsearchClientManager elasticsearchClientManager;
    private List<String> indexes = Lists.newArrayList();
    private List<String> types = Lists.newArrayList();
    private String[] withfields;
    private String[] withoutfields;
    private DateTime startDate;
    private DateTime endDate;
    private int limit = 1000*1000*1000; // we are going to set the default limit very high to 1bil
    private boolean random = false;
    private int threadPoolSize = 10;
    private int batchSize = 100;
    private String scrollTimeout = null;

    private ObjectMapper mapper = new ObjectMapper();

    private ElasticsearchConfiguration config;

    private ExecutorService executor;

    private QueryBuilder queryBuilder;
    private FilterBuilder filterBuilder;

    // These are private to help us manage the scroll
    private SearchRequestBuilder search;
    private SearchResponse scrollResp;
    private int scrollPositionInScroll = SCROLL_POSITION_NOT_INITIALIZED;
    private SearchHit next = null;
    private long totalHits = 0;
    private long totalRead = 0;

    public long getHitCount()                       { return this.search == null ? 0 : this.totalHits; }
    public long getReadCount()                      { return this.totalRead; }
    public double getReadPercent()                  { return (double)this.getReadCount() / (double)this.getHitCount(); }
    public long getRemainingCount()                 { return this.totalRead - this.totalHits; }
    public Iterator<SearchHit> iterator()           { return this; }
    private boolean isCompleted()                   { return totalRead >= this.limit && hasRecords(); }
    private boolean hasRecords()                    { return scrollPositionInScroll != -1 && (!(this.totalRead > this.limit)); }
    public SearchHit next()                         { return this.next; }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public void setScrollTimeout(String scrollTimeout) {
        this.scrollTimeout = scrollTimeout;
    }

    public void setQueryBuilder(QueryBuilder queryBuilder)      { this.queryBuilder = queryBuilder; }
    public void setFilterBuilder(FilterBuilder filterBuilder)   { this.filterBuilder = filterBuilder; }

    public ElasticsearchPersistReader() {
        Config config = StreamsConfigurator.config.getConfig("elasticsearch");
        this.config = ElasticsearchConfigurator.detectConfiguration(config);
    }
    public ElasticsearchPersistReader(ElasticsearchReaderConfiguration elasticsearchConfiguration) {
        this.elasticsearchClientManager = new ElasticsearchClientManager(elasticsearchConfiguration);
        indexes.add(elasticsearchConfiguration.getIndex());
        types.add(elasticsearchConfiguration.getType());
    }

    @Override
    public void startStream() {
        LOGGER.debug("startStream");
        executor = Executors.newSingleThreadExecutor();
        executor.submit(new ElasticsearchPersistReaderTask(this));
    }

    @Override
    public void prepare(Object o) {

        persistQueue = new ConcurrentLinkedQueue<StreamsDatum>();

        // If we haven't already set up the search, then set up the search.
        if(search == null)
        {
            search = elasticsearchClientManager.getClient()
                    .prepareSearch(indexes.toArray(new String[0]))
                    .setSearchType(SearchType.SCAN)
                    .setSize(Objects.firstNonNull(batchSize, DEFAULT_BATCH_SIZE).intValue())
                    .setScroll(Objects.firstNonNull(scrollTimeout, DEFAULT_SCROLL_TIMEOUT));

            if(this.queryBuilder != null)
                search.setQuery(this.queryBuilder);

            // If the types are null, then don't specify a type
            if(this.types != null && this.types.size() > 0)
                search = search.setTypes(types.toArray(new String[0]));

            Integer clauses = 0;
            if(this.withfields != null || this.withoutfields != null) {
                if( this.withfields != null )
                    clauses += this.withfields.length;
                if( this.withoutfields != null )
                    clauses += this.withoutfields.length;
            }

            List<FilterBuilder> filterList = buildFilterList();

            FilterBuilder allFilters = andFilters(filterList);

            if( clauses > 0 ) {
                //    search.setPostFilter(allFilters);
                search.setPostFilter(allFilters);
            }

            // TODO: Replace when all clusters are upgraded past 0.90.4 so we can implement a RANDOM scroll.
            if(this.random)
                search = search.addSort(SortBuilders.scriptSort("random()", "number"));
        }

        // We don't have a scroll, we need to create a scroll
        if(scrollResp == null) {
            scrollResp = search.execute().actionGet();
            LOGGER.trace(search.toString());
        }
    }

    public void setWithfields(String[] withfields) {
        this.withfields = withfields;
    }

    public void setWithoutfields(String[] withoutfields) {
        this.withoutfields = withoutfields;
    }

    public boolean hasNext()
    {
        calcNext();
        return hasRecords();
    }

    public void calcNext()
    {
        try
        {
            // We have exhausted our scroll create another scroll.
            if(scrollPositionInScroll == SCROLL_POSITION_NOT_INITIALIZED || scrollPositionInScroll >= scrollResp.getHits().getHits().length)
            {
                // reset the scroll position
                scrollPositionInScroll = 0;

                // get the next hits of the scroll
                scrollResp = elasticsearchClientManager.getClient()
                        .prepareSearchScroll(scrollResp.getScrollId())
                        .setScroll(Objects.firstNonNull(scrollTimeout, DEFAULT_SCROLL_TIMEOUT))
                        .execute()
                        .actionGet();

                this.totalHits = scrollResp.getHits().getTotalHits();
            }

            // If this scroll has 0 items then we set the scroll position to -1
            // letting the iterator know that we are done.
            if(scrollResp.getHits().getTotalHits() == 0 || scrollResp.getHits().getHits().length == 0)
                scrollPositionInScroll = -1;
            else
            {
                // get the next record
                next = scrollResp.getHits().getAt(scrollPositionInScroll);

                // Increment our counters
                scrollPositionInScroll += 1;
                totalRead += 1;
            }
        }
        catch(Exception e)
        {
            e.printStackTrace();
            LOGGER.error("Unexpected scrolling error: {}", e.getMessage());
            scrollPositionInScroll = -1;
            next = null;
        }
    }

    public void remove() { }

    // copied from elasticsearch
    // if we need this again we should factor it out into a utility
    private FilterBuilder andFilters(List<FilterBuilder> filters)
    {
        if(filters == null || filters.size() == 0)
            return null;

        FilterBuilder toReturn = filters.get(0);

        for(int i = 1; i < filters.size(); i++)
            toReturn = FilterBuilders.andFilter(toReturn, filters.get(i));

        return toReturn;
    }

    private FilterBuilder orFilters(List<FilterBuilder> filters)
    {
        if(filters == null || filters.size() == 0)
            return null;

        FilterBuilder toReturn = filters.get(0);

        for(int i = 1; i < filters.size(); i++)
            toReturn = FilterBuilders.orFilter(toReturn, filters.get(i));

        return toReturn;
    }

    private List<FilterBuilder> buildFilterList() {

        ArrayList<FilterBuilder> filterList = Lists.newArrayList();

        // If any withfields are specified, require that field be present
        //    There must a value set also for the document to be processed
        if(this.withfields != null && this.withfields.length > 0) {
            ArrayList<FilterBuilder> withFilterList = Lists.newArrayList();
            for( String withfield : this.withfields ) {
                FilterBuilder withFilter = FilterBuilders.existsFilter(withfield);
                withFilterList.add(withFilter);
            }
            //filterList.add(FilterBuilders.orFilter(orFilters(withFilterList)));
            filterList.add(withFilterList.get(0));
        }
        // If any withoutfields are specified, require that field not be present
        //    Document will be picked up even if present, if they do not have at least one value
        // this is annoying as it majorly impacts runtime
        // might be able to change behavior using null_field
        if(this.withoutfields != null && this.withoutfields.length > 0) {
            ArrayList<FilterBuilder> withoutFilterList = Lists.newArrayList();
            for( String withoutfield : this.withoutfields ) {
                FilterBuilder withoutFilter = FilterBuilders.missingFilter(withoutfield).existence(true).nullValue(false);
                withoutFilterList.add(withoutFilter);
            }
            //filterList.add(FilterBuilders.orFilter(orFilters(withoutFilterList)));
            filterList.add(withoutFilterList.get(0));
        }

        return filterList;
    }

    @Override
    public StreamsResultSet readCurrent() {

        LOGGER.debug("readCurrent: {}", persistQueue.size());

        Collection<StreamsDatum> currentIterator = Lists.newArrayList();
        Iterators.addAll(currentIterator, persistQueue.iterator());

        StreamsResultSet current = new StreamsResultSet(Queues.newConcurrentLinkedQueue(currentIterator));

        persistQueue.clear();

        return current;

    }

    public StreamsResultSet readAll() {
        return readCurrent();
    }

    @Override
    public StreamsResultSet readNew(BigInteger sequence) {
        return readCurrent();
    }

    @Override
    public StreamsResultSet readRange(DateTime start, DateTime end) {
        return readCurrent();
    }

    void shutdownAndAwaitTermination(ExecutorService pool) {
        pool.shutdown(); // Disable new tasks from being submitted
        try {
            // Wait a while for existing tasks to terminate
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                pool.shutdownNow(); // Cancel currently executing tasks
                // Wait a while for tasks to respond to being cancelled
                if (!pool.awaitTermination(10, TimeUnit.SECONDS))
                    System.err.println("Pool did not terminate");
            }
        } catch (InterruptedException ie) {
            // (Re-)Cancel if current thread also interrupted
            pool.shutdownNow();
            // Preserve interrupt status
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void cleanUp() {
        LOGGER.info("PersistReader done");
    }

}


