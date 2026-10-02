#!/bin/bash

# Burst Testing Script for Seat Reservation Service
# Simulates on-sale stampede with concurrent requests

set -e

BASE_URL="${1:-http://localhost:8080}"
NUM_USERS="${2:-100}"
REQUESTS_PER_USER="${3:-200}"
TOTAL_REQUESTS=$((NUM_USERS * REQUESTS_PER_USER))

echo "=========================================="
echo "Seat Reservation Burst Test"
echo "=========================================="
echo "Base URL: $BASE_URL"
echo "Users: $NUM_USERS"
echo "Requests per user: $REQUESTS_PER_USER"
echo "Total requests: $TOTAL_REQUESTS"
echo "=========================================="

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check if service is healthy
echo -n "Checking service health... "
HEALTH_CHECK=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/health/live")
if [ "$HEALTH_CHECK" -eq 200 ]; then
    echo -e "${GREEN}OK${NC}"
else
    echo -e "${RED}FAILED (HTTP $HEALTH_CHECK)${NC}"
    exit 1
fi

# Check if service is ready
echo -n "Checking service readiness... "
READY_CHECK=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/actuator/health")
if [ "$READY_CHECK" -eq 200 ]; then
    echo -e "${GREEN}OK${NC}"
else
    echo -e "${RED}FAILED (HTTP $READY_CHECK)${NC}"
    exit 1
fi

# Create a test show
echo -n "Creating test show... "
SHOW_RESPONSE=$(curl -s -X POST "$BASE_URL/shows" \
    -H "Content-Type: application/json" \
    -d '{
        "name": "burst-test-show",
        "seats": ["A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A9", "A10"],
        "price_paise": 25000
    }')

SHOW_ID=$(echo "$SHOW_RESPONSE" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

if [ -n "$SHOW_ID" ]; then
    echo -e "${GREEN}OK (Show ID: $SHOW_ID)${NC}"
else
    echo -e "${RED}FAILED${NC}"
    echo "Response: $SHOW_RESPONSE"
    exit 1
fi

# Generate JWT tokens for users
echo "Generating JWT tokens for $NUM_USERS users..."
USER_TOKENS=()
for i in $(seq 1 $NUM_USERS); do
    # For simplicity, we'll use a mock token generation
    # In a real scenario, you'd call an auth endpoint
    # Format: Bearer user-{i}
    USER_TOKENS+=("Bearer user-$i")
done

# Run burst test
echo ""
echo "Starting burst test..."
echo "=========================================="

# Track statistics
CONFIRMED=0
DECLINED_SEAT_TAKEN=0
DECLINED_USER_LIMIT=0
DECLINED_IDEMPOTENCY=0
ERRORS=0

# Create temporary directory for results
TEMP_DIR=$(mktemp -d)
trap "rm -rf $TEMP_DIR" EXIT

# Function to make reservation request
make_reservation() {
    local user_id=$1
    local show_id=$2
    local request_num=$3
    local token=$4

    # Alternate between hot seat (A1) and random seats
    if [ $((request_num % 10)) -eq 0 ]; then
        # Hot seat contention
        SEATS='["A1"]'
    else
        # Random seat
        SEAT_NUM=$(( (RANDOM % 10) + 1 ))
        SEATS="\"A${SEAT_NUM}\""
        SEATS="[$SEATS]"
    fi

    IDEMPOTENCY_KEY="user-${user_id}-req-${request_num}"

    RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/shows/$show_id/reserve" \
        -H "Content-Type: application/json" \
        -H "Authorization: $token" \
        -d "{
            \"seats\": $SEATS,
            \"idempotency_key\": \"$IDEMPOTENCY_KEY\"
        }")

    HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
    BODY=$(echo "$RESPONSE" | head -n-1)

    echo "$HTTP_CODE,$BODY" >> "$TEMP_DIR/results_${user_id}.txt"
}

# Export function for parallel execution
export -f make_reservation
export BASE_URL TEMP_DIR

# Run requests in parallel
echo "Launching $TOTAL_REQUESTS concurrent requests..."
for i in $(seq 1 $NUM_USERS); do
    USER_ID=$i
    TOKEN="${USER_TOKENS[$((i-1))]}"

    for j in $(seq 1 $REQUESTS_PER_USER); do
        make_reservation $USER_ID $SHOW_ID $j "$TOKEN" &
    done
done

# Wait for all background jobs to complete
wait

echo "All requests completed."

# Process results
echo ""
echo "Processing results..."
for file in "$TEMP_DIR"/results_*.txt; do
    while IFS=',' read -r http_code body; do
        case $http_code in
            201)
                ((CONFIRMED++))
                ;;
            409)
                if echo "$body" | grep -q "seat_not_available"; then
                    ((DECLINED_SEAT_TAKEN++))
                elif echo "$body" | grep -q "per_user_limit"; then
                    ((DECLINED_USER_LIMIT++))
                elif echo "$body" | grep -q "idempotency"; then
                    ((DECLINED_IDEMPOTENCY++))
                else
                    ((DECLINED_SEAT_TAKEN++))
                fi
                ;;
            5*)
                ((ERRORS++))
                ;;
        esac
    done < "$file"
done

# Get final show state
echo "Fetching final show state..."
FINAL_STATE=$(curl -s "$BASE_URL/shows/$SHOW_ID")

# Print results
echo ""
echo "=========================================="
echo "BURST TEST RESULTS"
echo "=========================================="
echo -e "Confirmed reservations:    ${GREEN}$CONFIRMED${NC}"
echo -e "Declined (seat taken):     ${YELLOW}$DECLINED_SEAT_TAKEN${NC}"
echo -e "Declined (user limit):     ${YELLOW}$DECLINED_USER_LIMIT${NC}"
echo -e "Declined (idempotency):    ${YELLOW}$DECLINED_IDEMPOTENCY${NC}"
echo -e "Server errors (5xx):       ${RED}$ERRORS${NC}"
echo "=========================================="
echo "Total requests:              $TOTAL_REQUESTS"
echo "Total processed:             $((CONFIRMED + DECLINED_SEAT_TAKEN + DECLINED_USER_LIMIT + DECLINED_IDEMPOTENCY + ERRORS))"
echo "=========================================="
echo ""
echo "Final Show State:"
echo "$FINAL_STATE" | python3 -m json.tool 2>/dev/null || echo "$FINAL_STATE"

# Validate reconciliation invariant
AVAILABLE=$(echo "$FINAL_STATE" | grep -o '"available":[0-9]*' | head -1 | cut -d':' -f2)
HELD=$(echo "$FINAL_STATE" | grep -o '"held":[0-9]*' | head -1 | cut -d':' -f2)
CONFIRMED_COUNT=$(echo "$FINAL_STATE" | grep -o '"confirmed":[0-9]*' | head -1 | cut -d':' -f2)
TOTAL=$(echo "$FINAL_STATE" | grep -o '"total":[0-9]*' | head -1 | cut -d':' -f2)

if [ -n "$AVAILABLE" ] && [ -n "$HELD" ] && [ -n "$CONFIRMED_COUNT" ] && [ -n "$TOTAL" ]; then
    SUM=$((AVAILABLE + HELD + CONFIRMED_COUNT))
    echo ""
    echo "Reconciliation Check:"
    echo "Available: $AVAILABLE"
    echo "Held: $HELD"
    echo "Confirmed: $CONFIRMED_COUNT"
    echo "Total: $TOTAL"
    echo "Sum (Available + Held + Confirmed): $SUM"

    if [ "$SUM" -eq "$TOTAL" ]; then
        echo -e "${GREEN}✓ Reconciliation invariant holds${NC}"
    else
        echo -e "${RED}✗ Reconciliation invariant VIOLATED${NC}"
        exit 1
    fi
else
    echo -e "${YELLOW}Warning: Could not parse reconciliation data${NC}"
fi

# Exit with error if there were 5xx errors
if [ "$ERRORS" -gt 0 ]; then
    echo -e "${RED}Test failed: $ERRORS server errors detected${NC}"
    exit 1
fi

echo -e "${GREEN}Burst test completed successfully${NC}"
