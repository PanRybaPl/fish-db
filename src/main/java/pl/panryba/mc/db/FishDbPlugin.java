/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package pl.panryba.mc.db;

import com.avaje.ebean.EbeanServer;
import com.avaje.ebean.EbeanServerFactory;
import com.avaje.ebean.config.DataSourceConfig;
import com.avaje.ebean.config.ServerConfig;
import com.avaje.ebeaninternal.api.SpiEbeanServer;
import com.avaje.ebeaninternal.server.ddl.DdlGenerator;
import com.avaje.ebeaninternal.server.lib.sql.TransactionIsolation;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URLConnection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FishDbPlugin extends JavaPlugin {
   
    public File getDbConfigFile() {
        File config = new File(getDataFolder(), "database.yml");
        return config;
    }
    
    public EbeanServer getCustomDatabase() {
        FileConfiguration config = new YamlConfiguration();
        
        File dbConfigFile = getDbConfigFile();
        
        try {
            config.load(dbConfigFile);
        } catch (IOException ex) {
            Logger.getLogger(FishDbPlugin.class.getName()).log(Level.SEVERE, null, ex);
        } catch (InvalidConfigurationException ex) {
            Logger.getLogger(FishDbPlugin.class.getName()).log(Level.SEVERE, null, ex);
        }
        
        ConfigurationSection dbSection = config.getConfigurationSection("database");

        EbeanServer database;

        if (dbSection != null) {
            String driver = dbSection.getString("driver");
            String url = dbSection.getString("url");
            String username = dbSection.getString("username");
            String password = dbSection.getString("password");
            String isolation = dbSection.getString("isolation");

            database = prepareDatabase(driver, url, username, password, isolation);

            try {
                SpiEbeanServer serv = (SpiEbeanServer) database;
                DdlGenerator gen = serv.getDdlGenerator();

                String createScript = gen.generateCreateDdl();
                this.getLogger().info(createScript);

                gen.runScript(false, createScript);
            } catch (Exception ex) {
                this.getLogger().info(ex.toString());
            }
        } else {
            database = this.getDatabase();
            try {
                this.installDDL();
            } catch (Exception ex) {
                getLogger().info(ex.toString());
            }
        }
        
        return database;
    }
    
    private String replaceDatabaseString(String input) {
        input = input.replaceAll("\\{DIR\\}", this.getDataFolder().getPath().replaceAll("\\\\", "/") + "/");
        input = input.replaceAll("\\{NAME\\}", this.getDescription().getName().replaceAll("[^\\w_-]", ""));

        return input;
    }    

    private EbeanServer prepareDatabase(String driver, String url, String username, String password, String isolation) {
        //Setup the data source
        DataSourceConfig ds = new DataSourceConfig();
        ds.setDriver(driver);
        ds.setUrl(replaceDatabaseString(url));
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setIsolationLevel(TransactionIsolation.getLevel(isolation));

        //Setup the server configuration
        ServerConfig sc = new ServerConfig();
        sc.setDefaultServer(false);
        sc.setRegister(false);
        sc.setName(ds.getUrl().replaceAll("[^a-zA-Z0-9]", ""));

        //Get all persistent classes
        List<Class<?>> classes = this.getDatabaseClasses();

        //Do a sanity check first
        if (classes.isEmpty()) {
            //Exception: There is no use in continuing to load this database
            throw new RuntimeException("Database has been enabled, but no classes are registered to it");
        }

        //Register them with the EbeanServer
        sc.setClasses(classes);

        //Finally the data source
        sc.setDataSourceConfig(ds);

        //Declare a few local variables for later use
        ClassLoader currentClassLoader = null;
        Field cacheField = null;
        boolean cacheValue = true;

        try {
            //Store the current ClassLoader, so it can be reverted later
            currentClassLoader = Thread.currentThread().getContextClassLoader();

            ClassLoader pluginClassLoader = getClassLoader();
            //Set the ClassLoader to Plugin ClassLoader
            Thread.currentThread().setContextClassLoader(pluginClassLoader);

            //Get a reference to the private static "defaultUseCaches"-field in URLConnection
            cacheField = URLConnection.class.getDeclaredField("defaultUseCaches");

            //Make it accessible, store the default value and set it to false
            cacheField.setAccessible(true);
            cacheValue = cacheField.getBoolean(null);
            cacheField.setBoolean(null, false);

            //Setup Ebean based on the configuration
            return EbeanServerFactory.create(sc);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to create a new instance of the EbeanServer", ex);
        } finally {
            //Revert the ClassLoader back to its original value
            if (currentClassLoader != null) {
                Thread.currentThread().setContextClassLoader(currentClassLoader);
            }

            //Revert the "defaultUseCaches"-field in URLConnection back to its original value
            try {
                if (cacheField != null) {
                    cacheField.setBoolean(null, cacheValue);
                }
            } catch (Exception e) {
                System.out.println("Failed to revert the \"defaultUseCaches\"-field back to its original value, URLConnection-caching remains disabled.");
            }
        }
    }
}
