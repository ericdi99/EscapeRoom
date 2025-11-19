package com.escaperoom.dao;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

public class DynamoDbClientProvider {

    private static final Region REGION = Region.US_EAST_1; // replace with your region
    private static DynamoDbClient dynamoDbClient;

    public static DynamoDbClient getClient() {
        if (dynamoDbClient == null) {
            dynamoDbClient = DynamoDbClient.builder()
                    .region(REGION)
                    .build();
        }
        return dynamoDbClient;
    }

    public static void close() {
        if (dynamoDbClient != null) {
            dynamoDbClient.close();
        }
    }
}