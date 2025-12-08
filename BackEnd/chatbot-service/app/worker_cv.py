from app.services.rabbitmq.cv_consumer import start_cv_consumer

if __name__ == "__main__":
    print("=" * 60)
    print("EMBEDDING CV WORKER")
    print("=" * 60)
    start_cv_consumer()