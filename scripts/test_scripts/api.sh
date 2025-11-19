#!/bin/bash

# api.sh — Simple command-line tester for Escape Room Reservation API
#
# This script provides convenient shortcuts to call your API Gateway endpoints
# without manually typing long curl commands each time.
#
# -------------------------------------------------------
# USAGE
# -------------------------------------------------------
#   ./api.sh <action> <parameter>
#
# ACTIONS:
#
#   create  "<json-body>"
#       Example:
#         ./api.sh create '{"userId":"user-001","roomId":"ROOM-1","slotId":"2025-11-19#10"}'
#
#   get     <reservationId>
#       Example:
#         ./api.sh get 12345
#
#   confirm <reservationId>
#       Example:
#         ./api.sh confirm a28b3374-5e89-4e9b
#
#   cancel  <reservationId>
#       Example:
#         ./api.sh cancel a28b3374-5e89-4e9b
# ----------------------------------------------------------------------------------------------

API_KEY="1TgyssJxKt39wkoU5N2hY9SPmk4tp75V8sVZRBwq"
BASE_URL="https://4mwg57u84j.execute-api.us-east-1.amazonaws.com/prod/reservations"

ACTION=$1
PARAM=$2

if [ -z "$ACTION" ] || [ -z "$PARAM" ]; then
    echo "Usage: $0 <create|cancel|confirm|get> <param>"
    exit 1
fi

CURL_CMD=""

case "$ACTION" in
    create)
        CURL_CMD="curl -X POST -H \"Content-Type: application/json\" -H \"x-api-key: $API_KEY\" -d '$PARAM' \"$BASE_URL/create\""
        ;;
    cancel)
        CURL_CMD="curl -X POST -H \"x-api-key: $API_KEY\" \"$BASE_URL/cancel/$PARAM\""
        ;;
    confirm)
        CURL_CMD="curl -X POST -H \"x-api-key: $API_KEY\" \"$BASE_URL/confirm/$PARAM\""
        ;;
    get)
        CURL_CMD="curl -H \"x-api-key: $API_KEY\" \"$BASE_URL/getReservationById/$PARAM\""
        ;;
    *)
        echo "Invalid action: $ACTION"
        echo "Valid actions: create, cancel, confirm, get"
        exit 1
        ;;
esac

# Output the curl command
echo "Executing: $CURL_CMD"
# Execute the curl command
eval $CURL_CMD
echo


