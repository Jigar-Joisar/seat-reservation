#!/usr/bin/env python3
"""
Burst Testing Script for Seat Reservation Service
Simulates on-sale stampede with concurrent requests
"""

import argparse
import json
import random
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import Dict, List, Tuple
import requests


class BurstTester:
    def __init__(self, base_url: str, num_users: int, requests_per_user: int):
        self.base_url = base_url.rstrip('/')
        self.num_users = num_users
        self.requests_per_user = requests_per_user
        self.total_requests = num_users * requests_per_user

        # Statistics
        self.confirmed = 0
        self.declined_seat_taken = 0
        self.declined_user_limit = 0
        self.declined_idempotency = 0
        self.errors = 0

    def check_health(self) -> bool:
        """Check if service is healthy and ready"""
        try:
            # Liveness check
            resp = requests.get(f"{self.base_url}/health/live", timeout=5)
            if resp.status_code != 200:
                print(f"❌ Liveness check failed: HTTP {resp.status_code}")
                return False

            # Readiness check
            resp = requests.get(f"{self.base_url}/actuator/health", timeout=5)
            if resp.status_code != 200:
                print(f"❌ Readiness check failed: HTTP {resp.status_code}")
                return False

            print("✓ Service is healthy and ready")
            return True
        except Exception as e:
            print(f"❌ Health check failed: {e}")
            return False

    def create_show(self) -> str:
        """Create a test show and return its ID"""
        try:
            payload = {
                "name": "burst-test-show",
                "seats": [f"A{i}" for i in range(1, 11)],  # A1-A10
                "price_paise": 25000
            }

            resp = requests.post(
                f"{self.base_url}/shows",
                json=payload,
                headers={"Content-Type": "application/json"},
                timeout=10
            )

            if resp.status_code == 201:
                show_id = resp.json()["id"]
                print(f"✓ Created test show: {show_id}")
                return show_id
            else:
                print(f"❌ Failed to create show: HTTP {resp.status_code}")
                print(f"Response: {resp.text}")
                sys.exit(1)
        except Exception as e:
            print(f"❌ Failed to create show: {e}")
            sys.exit(1)

    def make_reservation(self, user_id: int, show_id: str, request_num: int) -> Tuple[int, str]:
        """Make a single reservation request"""
        try:
            # Alternate between hot seat (A1) and random seats
            if request_num % 10 == 0:
                # Hot seat contention
                seats = ["A1"]
            else:
                # Random seat
                seat_num = random.randint(1, 10)
                seats = [f"A{seat_num}"]

            idempotency_key = f"user-{user_id}-req-{request_num}"

            # Use mock JWT token (in production, you'd use real tokens)
            token = f"Bearer user-{user_id}"

            payload = {
                "seats": seats,
                "idempotency_key": idempotency_key
            }

            resp = requests.post(
                f"{self.base_url}/shows/{show_id}/reserve",
                json=payload,
                headers={
                    "Content-Type": "application/json",
                    "Authorization": token
                },
                timeout=30
            )

            return resp.status_code, resp.text
        except Exception as e:
            return 500, str(e)

    def process_result(self, status_code: int, body: str):
        """Process a single result and update statistics"""
        if status_code == 201:
            self.confirmed += 1
        elif status_code == 409:
            try:
                error_data = json.loads(body)
                error_type = error_data.get("error", "")
                if "seat_not_available" in error_type:
                    self.declined_seat_taken += 1
                elif "per_user_limit" in error_type:
                    self.declined_user_limit += 1
                elif "idempotency" in error_type:
                    self.declined_idempotency += 1
                else:
                    self.declined_seat_taken += 1
            except:
                self.declined_seat_taken += 1
        elif status_code >= 500:
            self.errors += 1

    def run_burst_test(self, show_id: str):
        """Run the burst test with concurrent requests"""
        print(f"\n🚀 Launching {self.total_requests} concurrent requests...")

        with ThreadPoolExecutor(max_workers=200) as executor:
            futures = []

            for user_id in range(1, self.num_users + 1):
                for request_num in range(1, self.requests_per_user + 1):
                    future = executor.submit(
                        self.make_reservation,
                        user_id,
                        show_id,
                        request_num
                    )
                    futures.append(future)

            # Process results as they complete
            for future in as_completed(futures):
                status_code, body = future.result()
                self.process_result(status_code, body)

        print("✓ All requests completed")

    def get_final_state(self, show_id: str) -> Dict:
        """Get the final state of the show"""
        try:
            resp = requests.get(f"{self.base_url}/shows/{show_id}", timeout=10)
            if resp.status_code == 200:
                return resp.json()
            else:
                print(f"⚠ Failed to get final state: HTTP {resp.status_code}")
                return {}
        except Exception as e:
            print(f"⚠ Failed to get final state: {e}")
            return {}

    def print_results(self, final_state: Dict):
        """Print the test results"""
        print("\n" + "=" * 50)
        print("BURST TEST RESULTS")
        print("=" * 50)
        print(f"Confirmed reservations:    {self.confirmed}")
        print(f"Declined (seat taken):     {self.declined_seat_taken}")
        print(f"Declined (user limit):     {self.declined_user_limit}")
        print(f"Declined (idempotency):    {self.declined_idempotency}")
        print(f"Server errors (5xx):       {self.errors}")
        print("=" * 50)
        print(f"Total requests:              {self.total_requests}")
        total_processed = (self.confirmed + self.declined_seat_taken +
                          self.declined_user_limit + self.declined_idempotency +
                          self.errors)
        print(f"Total processed:             {total_processed}")
        print("=" * 50)

        if final_state:
            print("\nFinal Show State:")
            print(json.dumps(final_state, indent=2))

            # Validate reconciliation invariant
            counts = final_state.get("counts", {})
            available = counts.get("available", 0)
            held = counts.get("held", 0)
            confirmed = counts.get("confirmed", 0)
            total = counts.get("total", 0)

            print("\nReconciliation Check:")
            print(f"Available: {available}")
            print(f"Held: {held}")
            print(f"Confirmed: {confirmed}")
            print(f"Total: {total}")
            sum_val = available + held + confirmed
            print(f"Sum (Available + Held + Confirmed): {sum_val}")

            if sum_val == total:
                print("✓ Reconciliation invariant holds")
            else:
                print("✗ Reconciliation invariant VIOLATED")
                sys.exit(1)

        if self.errors > 0:
            print(f"\n❌ Test failed: {self.errors} server errors detected")
            sys.exit(1)

        print("\n✅ Burst test completed successfully")


def main():
    parser = argparse.ArgumentParser(description="Burst test for Seat Reservation Service")
    parser.add_argument("base_url", help="Base URL of the service (e.g., http://localhost:8080)")
    parser.add_argument("--users", type=int, default=100, help="Number of users (default: 100)")
    parser.add_argument("--requests", type=int, default=200, help="Requests per user (default: 200)")
    args = parser.parse_args()

    print("=" * 50)
    print("Seat Reservation Burst Test")
    print("=" * 50)
    print(f"Base URL: {args.base_url}")
    print(f"Users: {args.users}")
    print(f"Requests per user: {args.requests}")
    print(f"Total requests: {args.users * args.requests}")
    print("=" * 50)

    tester = BurstTester(args.base_url, args.users, args.requests)

    # Check health
    if not tester.check_health():
        sys.exit(1)

    # Create show
    show_id = tester.create_show()

    # Run burst test
    start_time = time.time()
    tester.run_burst_test(show_id)
    duration = time.time() - start_time
    print(f"\nTest duration: {duration:.2f} seconds")

    # Get final state
    final_state = tester.get_final_state(show_id)

    # Print results
    tester.print_results(final_state)


if __name__ == "__main__":
    main()
