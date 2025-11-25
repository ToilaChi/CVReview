package org.example.recruitmentservice.services.chunking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import lombok.Getter;
import lombok.Setter;
import java.util.Set;

@Configuration
@ConfigurationProperties(prefix = "chunking")
@Getter
@Setter
public class ChunkingConfig {
    // Chunking parameters
    private int minTokens = 120;
    private int maxTokens = 300;
    private int overlapTokens = 30;
    private double tokensPerWord = 1.3;

    // Summary parameters
    private int documentSummaryMaxWords = 50;
    private int sectionSummaryMaxWords = 30;
    private int chunkSummaryMaxWords = 20;

    // Seniority thresholds
    private int juniorMaxYears = 2;
    private int midMaxYears = 5;

    // Tech keywords
    private Set<String> techKeywords = Set.of(
            "Java", "Python", "JavaScript", "TypeScript", "C++", "C#", "Go", "Rust",
            "Kotlin", "Swift", "Ruby", "PHP", "Scala", "R", "Dart", "Objective-C",

            // Backend Frameworks
            "Spring Boot", "Spring Framework", "Node.js", "Express.js", "Django",
            "Flask", "FastAPI", ".NET", ".NET Core", "Ruby on Rails", "Laravel",

            // Frontend Frameworks & Libraries
            "React", "React.js", "Next.js", "Angular", "Vue.js", "Svelte", "jQuery",
            "Redux", "Zustand", "MobX", "RxJS", "Webpack", "Vite",

            // Mobile Development
            "React Native", "Flutter", "Android", "iOS", "Xamarin", "Ionic",

            // Databases
            "PostgreSQL", "MySQL", "MongoDB", "Redis", "Cassandra", "DynamoDB",
            "MariaDB", "SQLite", "Oracle", "SQL Server", "Neo4j", "CouchDB",
            "Elasticsearch", "Memcached",

            // Cloud Platforms
            "AWS", "Azure", "GCP", "Google Cloud", "DigitalOcean", "Heroku",
            "AWS Lambda", "S3", "EC2", "ECS", "EKS", "RDS", "CloudFront",

            // DevOps & Infrastructure
            "Docker", "Kubernetes", "Jenkins", "GitLab CI", "GitHub Actions",
            "CircleCI", "Terraform", "Ansible", "Chef", "Puppet", "Vagrant",
            "Prometheus", "Grafana", "ELK Stack", "Splunk", "Datadog",

            // Big Data & Analytics
            "Hadoop", "Spark", "Kafka", "RabbitMQ", "Flink", "Airflow",
            "Hive", "Presto", "Tableau", "Power BI",

            // AI/ML
            "TensorFlow", "PyTorch", "Scikit-learn", "Keras", "OpenCV",
            "NLP", "Computer Vision", "Machine Learning", "Deep Learning",

            // Web Technologies
            "HTML5", "CSS3", "SASS", "LESS", "Tailwind CSS", "Bootstrap",
            "Material UI", "Ant Design",

            // API & Protocols
            "REST API", "RESTful", "GraphQL", "gRPC", "WebSocket", "SOAP",
            "OAuth", "JWT", "OpenAPI", "Swagger",

            // Testing
            "Jest", "Mocha", "Cypress", "Selenium", "JUnit", "Mockito",
            "TestNG", "Pytest", "JMeter", "Postman", "TDD", "BDD",

            // Version Control & Collaboration
            "Git", "GitHub", "GitLab", "Bitbucket", "SVN",
            "Jira", "Confluence", "Trello", "Asana",

            // Methodologies
            "Agile", "Scrum", "Kanban", "Waterfall", "DevOps", "CI/CD",
            "Microservices", "Serverless", "Event-Driven"
    );
}
