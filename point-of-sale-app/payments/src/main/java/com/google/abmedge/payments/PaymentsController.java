// Copyright 2021 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.abmedge.payments;

import com.google.abmedge.dto.Payment;
import com.google.abmedge.payments.dao.InMemoryPaymentGateway;
import com.google.abmedge.payments.dao.PaymentGateway;
import com.google.abmedge.payments.dto.Bill;
import com.google.gson.Gson;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * This is the main controller class for the payments service defines the APIs exposed by the
 * service. The controller also defines 2 APIs (/ready and /healthy) for readiness and health
 * checkups
 */
@RestController
public class PaymentsController {

  private static final Logger LOGGER = LogManager.getLogger(PaymentsController.class);
  private static final String IN_MEMORY_GATEWAY = "IN_MEMORY";
  private static final String PAYMENT_GW_TYPE_ENV_VAR = "PAYMENT_GW";
  private static final Gson GSON = new Gson();
  private static final Map<String, PaymentGateway> paymentGatewayMap =
      new HashMap<>() {
        {
          put(IN_MEMORY_GATEWAY, new InMemoryPaymentGateway());
        }
      };
  private PaymentGateway activePaymentGateway;

  /**
   * This method runs soon after the object for this class is created on startup of the application.
   */
  @PostConstruct
  void init() {
    initConnectorType();
  }

  @RequestMapping("/")
  public String home() {
    return "Hello Anthos BareMetal - Payments Controller";
  }

  /**
   * Readiness probe endpoint.
   *
   * @return HTTP Status 200 if server is ready to receive requests.
   */
  @GetMapping("/ready")
  @ResponseStatus(HttpStatus.OK)
  public String readiness() {
    return "ok";
  }

  /**
   * Liveness probe endpoint.
   *
   * @return HTTP Status 200 if server is healthy and serving requests.
   */
  @GetMapping("/healthy")
  public ResponseEntity<String> liveness() {
    // TODO:: Add suitable liveness check
    return new ResponseEntity<>("ok", HttpStatus.OK);
  }

  /**
   * This method binds the controller to the '/pay' API requests. The method takes in an argument of
   * {@link Payment} and uses the currently active payment gateway's ({@link
   * PaymentsController#activePaymentGateway}) implementation to process the payment. As a response
   * to this request the API returns a bill with all the details.
   *
   * @param payment an object of type {@link Payment} containing details of all the items purchased
   *     in this payment, the amount paid and the type of payment
   * @return a bill for the payment that was processed in string format
   */
  @PostMapping(value = "/pay", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> pay(@RequestBody Payment payment) {
    String jsonString;
    try {
      Bill bill = this.activePaymentGateway.pay(payment);
      jsonString = GSON.toJson(bill, Bill.class);
    } catch (Exception ex) {
      String msg =
          String.format(
              "Failed to process payment id '%s' with amount $%s",
              payment.getId(), payment.getPaidAmount());
      LOGGER.error(msg, ex);
      return new ResponseEntity<>(msg, HttpStatus.INTERNAL_SERVER_ERROR);
    }
    return new ResponseEntity<>(jsonString, HttpStatus.OK);
  }

  /**
   * This method initializes the payment gateway type. There can be multiple payment gateways
   * available at the same time. Each of them will be an implementation of the {@link
   * PaymentGateway} interface. However, only one of them will be active at any given time. In order
   * to change/reconfigure the {@link #activePaymentGateway} a restart of the application is
   * required.
   *
   * <p>The payment gateway type which is active is decided at startup time by looking at the
   * 'PAYMENT_GW' environment variable. If this environment variable is not set or if an invalid
   * value is set then by default the gateway type 'IN_MEMORY' is used.
   *
   * <p>The 'IN_MEMORY' gateway is implemented by {@link InMemoryPaymentGateway} class and it stores
   * all payment information in an in-memory datastore.
   *
   * <p>An invalid gateway type means that their is no {@link PaymentGateway} object stored against
   * the value set for the 'PAYMENT_GW' environment variable in the {@link #paymentGatewayMap}
   */
  private void initConnectorType() {
    String connectorType = System.getenv(PAYMENT_GW_TYPE_ENV_VAR);
    if (StringUtils.isBlank(connectorType) || !paymentGatewayMap.containsKey(connectorType)) {
      LOGGER.warn(
          String.format(
              "'%s' environment variable is not set; " + "thus defaulting to: %s",
              PAYMENT_GW_TYPE_ENV_VAR, IN_MEMORY_GATEWAY));
      connectorType = IN_MEMORY_GATEWAY;
    }
    activePaymentGateway = paymentGatewayMap.get(connectorType);
    LOGGER.info(String.format("Active connector type is: %s", connectorType));
  }
}
