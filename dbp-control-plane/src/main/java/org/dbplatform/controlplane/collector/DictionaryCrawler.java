package org.dbplatform.controlplane.collector;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.dbplatform.controlplane.domain.DatabaseInstance;
import org.dbplatform.controlplane.domain.Enums;

/** Reads the data dictionary of one engine (restricted to the configured schemas). */
public interface DictionaryCrawler {
    Enums.Engine engine();
    Model.CrawlResult crawl(Connection c, DatabaseInstance db, List<String> schemas) throws SQLException;
}
