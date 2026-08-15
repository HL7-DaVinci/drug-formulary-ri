package org.hl7.davinci.datainitializer;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties
public class DataInitializerProperties {

  private List<String> initialData;

  public List<String> getInitialData() {
    return initialData;
  }

  public void setInitialData(List<String> initialData) {
    this.initialData = initialData;
  }
  
}