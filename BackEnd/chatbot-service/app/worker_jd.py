from app.services.rabbitmq.jd_comsumer import start_jd_consumer 

if __name__ == "__main__":
    print("=" * 60)
    print("EMBEDDING JD WORKER")
    print("=" * 60)
    start_jd_consumer()