# ReddiSearch

**ReddiSearch** is an intelligent Reddit-powered search and synthesis engine. It crawls relevant Reddit threads, embeds the content into vector representations, and uses that vectorized knowledge to answer niche or nuanced questions — no need to post or wait for responses.

## 🚀 Features

- 🔍 **Contextual Search**: Automatically discovers and queries relevant subreddits and threads based on user input.
- 🧠 **Vector Embedding**: Converts Reddit discussions into vector space using state-of-the-art embeddings for semantic understanding.
- 🗣️ **Answer Synthesis**: Uses natural language generation to synthesize answers from retrieved content, providing clear and concise responses.
- 🌐 **No Posting Required**: Skip the wait for replies — get answers based on existing community knowledge.
- 🛠️ **Modular Pipeline**: Easily extendable for custom data sources, embedding models, or LLMs.

## 📦 How It Works

1. **Query Input**: User submits a niche question.
2. **Reddit Scraper**: Relevant posts and comment threads are fetched using the Reddit API (via `PRAW` or a custom scraper).
3. **Text Embedding**: Threads are vectorized using models like OpenAI, Hugging Face Transformers, or Sentence Transformers.
4. **Vector Search**: Performs similarity search to find contextually relevant discussions.
5. **LLM Synthesis**: Synthesizes a cohesive, human-readable response from the top-ranked threads.
6. **Response Output**: Presents the answer, with citations or links to original threads when desired.
