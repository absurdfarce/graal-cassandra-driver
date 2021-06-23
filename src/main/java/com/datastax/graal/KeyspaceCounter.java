package com.datastax.graal;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.Row;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.varia.NullAppender;

import java.net.InetSocketAddress;

/**
 * Extremely simple command-line application which counts and displays the number of keyspaces in 
 * the local instance.
 */
public class KeyspaceCounter {

  public static void main(String[] args) throws Exception {

    if (args.length > 0 && args[0].equalsIgnoreCase("debug")) {
      BasicConfigurator.configure();
    } else {
      BasicConfigurator.configure(new NullAppender());
    }

    /* Graal does not currently support runtime class generation via something like ASM so we
       explicitly fall back to jnr.ffi.provider.jffi.ReflectionLibraryLoader here */
    //System.setProperty("jnr.ffi.asm.enabled","false");

    CqlSession session =
        CqlSession.builder()
            .addContactPoint(new InetSocketAddress("localhost", 9042))
            .withLocalDatacenter("datacenter1")
            .build();

    int result = 0;
    for (Row row : session.execute("select keyspace_name from system_schema.keyspaces")) {
      ++result;
    }
    System.out.println(result);

    session.close();
  }
}
