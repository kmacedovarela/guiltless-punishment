/*
 * Copyright 2016-2017 Red Hat, Inc, and individual contributors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.openliberty.example;

import static io.restassured.RestAssured.get;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonValue;
import javax.json.JsonWriter;

import org.arquillian.cube.kubernetes.api.Session;
import org.arquillian.cube.openshift.impl.enricher.AwaitRoute;
import org.arquillian.cube.openshift.impl.enricher.RouteURL;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.internal.readiness.Readiness;
import io.fabric8.openshift.client.OpenShiftClient;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

@RunWith(Arquillian.class)
public class OpenshiftIT {
	
	@ArquillianResource
	private OpenShiftClient oc;
	
	@ArquillianResource
	private Session session;
	
	private static final String APP_NAME = System.getProperty("app.name");
	
    @RouteURL(value = "${app.name}", path = "/api/fruits")
    @AwaitRoute(path = "/")
    private String url;

    private static boolean restart = true;
    
    @Before
    public void setup() {    	
    	if ( restart ) {
    		System.out.println("Performing initialization");
    		// This is a hacky way to make sure the environment variables for the database service are available. 
    		// Using a boolean rather than @BeforeClass to avoid making the entire world static
    		rolloutChanges();
    		restart = false;
    	}
    	
        String jsonData =
                given()
                        .baseUri(url)
                .when()
                        .get()
                .then()
                        .extract().asString();

        System.out.println(jsonData);
        JsonArray array = Json.createReader(new StringReader(jsonData)).readArray();
        array.forEach(val -> {
            given()
                    .baseUri(url)
            .when()
                    .delete("/" + ((JsonObject) val).getInt("id"))
            .then()
                    .statusCode(204);
        });
    }

    @Test
    public void retrieveNoFruit() {
        given()
                .baseUri(url)
        .when()
                .get()
        .then()
                .statusCode(200)
                .body(is("[]"));
    }

    @Test
    public void oneFruit() throws Exception {
        createFruit("Peach");

        String payload =
                given()
                        .baseUri(url)
                .when()
                        .get()
                .then()
                        .statusCode(200)
                        .extract().asString();

        JsonArray array = Json.createReader(new StringReader(payload)).readArray();

        assertThat(array).hasSize(1);
        assertThat(array.get(0).getValueType()).isEqualTo(JsonValue.ValueType.OBJECT);

        JsonObject obj = (JsonObject) array.get(0);
        assertThat(obj.getInt("id")).isNotNull().isGreaterThan(0);

        given()
                .baseUri(url)
        .when()
                .pathParam("fruitId", obj.getInt("id"))
                .get("/{fruitId}")
        .then()
                .statusCode(200)
                .body(containsString("Peach"));
    }

    @Test
    public void createFruit() {
        String payload =
                given()
                        .baseUri(url)
                .when()
                        .contentType(ContentType.JSON)
                        .body(convert(Json.createObjectBuilder().add("name", "Raspberry").build()))
                        .post()
                .then()
                        .statusCode(201)
                        .extract().asString();

        JsonObject obj = Json.createReader(new StringReader(payload)).readObject();
        assertThat(obj).isNotNull();
        assertThat(obj.getInt("id")).isNotNull().isGreaterThan(0);
        assertThat(obj.getString("name")).isNotNull().isEqualTo("Raspberry");
    }

    @Test
    public void createInvalidPayload() {
        given()
                .baseUri(url)
        .when()
                .contentType(ContentType.TEXT)
                .body("")
                .post()
        .then()
                .statusCode(415);
    }

    @Test
    public void createIllegalPayload() {
        Fruit badFruit = new Fruit("Carrot");
        badFruit.setId(2);

        String payload =
                given()
                        .baseUri(url)
                .when()
                        .contentType(ContentType.JSON)
                        .body(badFruit)
                        .post()
                .then()
                        .statusCode(422)
                        .extract().asString();

        JsonObject obj = Json.createReader(new StringReader(payload)).readObject();
        assertThat(obj).isNotNull();
        assertThat(obj.getString("error")).isNotNull();
        assertThat(obj.getInt("code")).isNotNull().isEqualTo(422);
    }

    @Test
    public void update() throws Exception {
        Fruit pear = createFruit("Pear");

        String response =
                given()
                        .baseUri(url)
                .when()
                        .pathParam("fruitId", pear.getId())
                        .get("/{fruitId}")
                .then()
                        .statusCode(200)
                        .extract().asString();

        pear = new ObjectMapper().readValue(response, Fruit.class);

        pear.setName("Not Pear");

        response =
                given()
                        .baseUri(url)
                .when()
                        .pathParam("fruitId", pear.getId())
                        .contentType(ContentType.JSON)
                        .body(new ObjectMapper().writeValueAsString(pear))
                        .put("/{fruitId}")
                .then()
                        .statusCode(200)
                        .extract().asString();

        Fruit updatedPear = new ObjectMapper().readValue(response, Fruit.class);

        assertThat(pear.getId()).isEqualTo(updatedPear.getId());
        assertThat(updatedPear.getName()).isEqualTo("Not Pear");
    }

    @Test
    public void updateWithUnknownId() throws Exception {
        Fruit bad = new Fruit("bad");
        bad.setId(12345678);

        given()
                .baseUri(url)
        .when()
                .pathParam("fruitId", bad.getId())
                .contentType(ContentType.JSON)
                .body(new ObjectMapper().writeValueAsString(bad))
                .put("/{fruitId}")
        .then()
                .statusCode(404)
                .extract().asString();
    }

    @Test
    public void updateInvalidPayload() {
        given()
                .baseUri(url)
        .when()
                .contentType(ContentType.TEXT)
                .body("")
                .post()
        .then()
                .statusCode(415);
    }

    @Test
    public void updateIllegalPayload() throws Exception {
        Fruit carrot = createFruit("Carrot");
        carrot.setName(null);

        String payload =
                given()
                        .baseUri(url)
                .when()
                        .pathParam("fruitId", carrot.getId())
                        .contentType(ContentType.JSON)
                        .body(new ObjectMapper().writeValueAsString(carrot))
                        .put("/{fruitId}")
                .then()
                        .statusCode(422)
                        .extract().asString();

        JsonObject obj = Json.createReader(new StringReader(payload)).readObject();
        assertThat(obj).isNotNull();
        assertThat(obj.getString("error")).isNotNull();
        assertThat(obj.getInt("code")).isNotNull().isEqualTo(422);
    }

    @Test
    public void testDelete() throws Exception {
        Fruit orange = createFruit("Orange");

        given()
                .baseUri(url)
        .when()
                .delete("/" + orange.getId())
        .then()
                .statusCode(204);

        given()
                .baseUri(url)
        .when()
                .get()
        .then()
                .statusCode(200)
                .body(is("[]"));
    }

    @Test
    public void deleteWithUnknownId() {
        given()
                .baseUri(url)
        .when()
                .delete("/unknown")
        .then()
                .statusCode(404);

        given()
                .baseUri(url)
        .when()
                .get()
        .then()
                .statusCode(200)
                .body(is("[]"));
    }

    private Fruit createFruit(String name) throws Exception {
        String payload =
                given()
                        .baseUri(url)
                .when()
                        .contentType(ContentType.JSON)
                        .body(convert(Json.createObjectBuilder().add("name", name).build()))
                        .post()
                .then()
                        .statusCode(201)
                        .extract().asString();

        JsonObject obj = Json.createReader(new StringReader(payload)).readObject();
        assertThat(obj).isNotNull();
        assertThat(obj.getInt("id")).isNotNull().isGreaterThan(0);

        return new ObjectMapper().readValue(payload, Fruit.class);
    }

    private String convert(JsonObject object) {
        StringWriter stringWriter = new StringWriter();
        JsonWriter jsonWriter = Json.createWriter(stringWriter);
        jsonWriter.writeObject(object);
        jsonWriter.close();
        return stringWriter.toString();
    }
    

   	

	
	private void rolloutChanges() {
		System.out.println("Rollout changes to " + APP_NAME);

		// in reality, user would do `oc rollout latest`, but that's hard (racy) to wait
		// for
		// so here, we'll scale down to 0, wait for that, then scale back to 1 and wait
		// again
		scale(APP_NAME, 0);
		scale(APP_NAME, 1);

		await().atMost(5, TimeUnit.MINUTES).until(() -> {
			try {
				Response response = get(url);
				return response.getStatusCode() == 200;
			} catch (Exception e) {
				return false;
			}
		});
	}

	private void scale(String name, int replicas) {
		oc.deploymentConfigs().inNamespace(session.getNamespace()).withName(name).scale(replicas);

		await().atMost(5, TimeUnit.MINUTES).until(() -> {
			// ideally, we'd look at deployment config's status.availableReplicas field,
			// but that's only available since OpenShift 3.5
			List<Pod> pods = oc.pods().inNamespace(session.getNamespace()).withLabel("deploymentconfig", name).list()
					.getItems();
			try {
				return pods.size() == replicas && pods.stream().allMatch(Readiness::isPodReady);
			} catch (IllegalStateException e) {
				// the 'Ready' condition can be missing sometimes, in which case
				// Readiness.isPodReady throws an exception
				// here, we'll swallow that exception in hope that the 'Ready' condition will
				// appear later
				return false;
			}
		});
	}
}
