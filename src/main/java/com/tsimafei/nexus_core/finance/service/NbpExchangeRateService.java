package com.tsimafei.nexus_core.finance.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class NbpExchangeRateService {

    private static final Logger log = LoggerFactory.getLogger(NbpExchangeRateService.class);
    private final RestTemplate restTemplate = new RestTemplate();
    private static final String NBP_API_URL = "https://api.nbp.pl/api/exchangerates/rates/A/{code}/?format=json";

    public BigDecimal getRate(String currencyCode) {
        if ("PLN".equalsIgnoreCase(currencyCode)) {
            return BigDecimal.ONE;
        }

        try {
            String url = NBP_API_URL.replace("{code}", currencyCode.toLowerCase());
            Map response = restTemplate.getForObject(url, Map.class);

            if (response != null && response.containsKey("rates")) {
                List rates = (List) response.get("rates");
                if (!rates.isEmpty()) {
                    Map rateData = (Map) rates.get(0);
                    Object mid = rateData.get("mid");
                    return new BigDecimal(mid.toString());
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch NBP rate for currency: {}", currencyCode, e);
        }

        return BigDecimal.ZERO;
    }
}