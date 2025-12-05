from app.services.rabbitmq import start_consumer

if __name__ == "__main__":
    print("=" * 60)
    print("JD EMBEDDING WORKER")
    print("=" * 60)
    start_consumer()